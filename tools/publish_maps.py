"""Publish a map generation (tools/build_grid.py + split_grid.py output) so that no phone ever sees a half-published map.

usage: python tools/publish_maps.py DIST [plan|upload|switch|cleanup] [--repo gh:danrave1234/daanvia | dir:PATH]
                                   [--check 3] [--yes]

Layout on GitHub (a release holds at most 1000 files and one generation is ~560, so each generation gets its own):
  maps-<generation>              a prerelease, never "latest": the generation's cell and base zips (named after their
                                 sha256) and its areas-index.json. Never changed once uploaded.
  maps / areas-current.json      the live generation's areas-index.json. Switching it is the last step of a publish.
  maps / areas-index.json + zips the last build before generations, for app versions up to 0.8.0, which read only
                                 that file. Left as it is; remove it once nobody runs those versions.

  plan     (default, changes nothing) what upload would send and what cleanup would remove.
  upload   creates maps-<generation> and uploads every file of DIST/areas-index.json it does not have yet, then the
           index itself. DIST/areas-index.json first gains "release" (where its files are) and "previous" (the
           generation that is live now). A name already in the release must have the same size.
  switch   checks the generation's release is complete, downloads --check files (and the index) to compare bytes,
           then makes it live: maps/areas-current.next.json is uploaded, areas-current.json deleted and the new one
           renamed into place. Between the delete and the rename (one API call) the file is briefly missing; the app
           then reads the old areas-index.json or treats it like being offline.
  cleanup  lists maps-g* releases that are neither live, the live one's "previous", nor newer than the live one
           (uploaded, not switched yet); with --yes deletes them and their tags. Run it after a switch: the previous
           generation stays, so a phone that read the old index just before the switch still finishes its download.

--repo dir:PATH works on a folder (one subfolder per release) instead of GitHub: for tests, or for a debug build
pointed at a local server. upload, switch and cleanup --yes change public releases: only when the owner said so.
"""
import argparse, hashlib, json, os, pathlib, random, re, shutil, subprocess, sys, tempfile, urllib.error, urllib.request

HOME, POINTER, NEXT, INDEX = 'maps', 'areas-current.json', 'areas-current.next.json', 'areas-index.json'
GEN = re.compile(r'^maps-(g\d{8}T\d{6}Z)$')
MAX_FILES = 1000  # GitHub's limit per release


def files_of(index): return [c['file'] for c in index.get('cells', [])] + [b['file'] for b in index.get('base', [])]
def sizes_of(index): return {**{c['file']: c['bytes'] for c in index.get('cells', [])}, **{b['file']: b['bytes'] for b in index.get('base', [])}}
def sha_of(index): return {**{c['file']: c.get('sha256', '') for c in index.get('cells', [])}, **{b['file']: b.get('sha256', '') for b in index.get('base', [])}}
def release_of(gen): return 'maps-' + gen


class DirRepo:
    """A folder standing in for the repository's releases: one subfolder per release tag."""
    def __init__(self, path): self.root = pathlib.Path(path); self.root.mkdir(parents=True, exist_ok=True)
    def releases(self): return {d.name for d in self.root.iterdir() if d.is_dir()}
    def create(self, tag, notes): (self.root / tag).mkdir()
    def assets(self, tag):
        d = self.root / tag; return {f.name: f.stat().st_size for f in d.iterdir() if f.is_file() and not f.name.endswith('.part')} if d.is_dir() else {}
    def read(self, tag, name):
        f = self.root / tag / name; return f.read_bytes() if f.is_file() else None
    def upload(self, tag, items):
        for path, name in items:
            part = self.root / tag / (name + '.part'); shutil.copyfile(path, part); os.replace(part, self.root / tag / name)
    def delete(self, tag, name): (self.root / tag / name).unlink()
    def rename(self, tag, old, new):
        if (self.root / tag / new).exists(): raise RuntimeError(new + ' already exists')
        os.replace(self.root / tag / old, self.root / tag / new)
    def drop(self, tag): shutil.rmtree(self.root / tag)


class GitHubRepo:
    """Releases of one GitHub repository, through the gh CLI (already logged in) and public download URLs."""
    def __init__(self, repo): self.repo = repo; self._ids = {}
    def gh(self, *a, capture=True):
        r = subprocess.run(['gh', *a], capture_output=capture, text=True)
        if r.returncode: raise RuntimeError('gh %s failed: %s' % (' '.join(a[:3]), (r.stderr or '').strip()[:300]))
        return r.stdout
    def releases(self):
        return {t for t in self.gh('api', '--paginate', 'repos/%s/releases?per_page=100' % self.repo, '--jq', '.[].tag_name').split() if t == HOME or GEN.match(t)}
    def create(self, tag, notes):
        self.gh('release', 'create', tag, '-R', self.repo, '--prerelease', '--latest=false', '--title', 'Map data ' + tag[5:], '--notes', notes)
    def _list(self, tag):
        r = subprocess.run(['gh', 'api', 'repos/%s/releases/tags/%s' % (self.repo, tag)], capture_output=True, text=True)
        if r.returncode: return {}
        rid = json.loads(r.stdout)['id']; out = {}
        for row in self.gh('api', '--paginate', 'repos/%s/releases/%d/assets?per_page=100' % (self.repo, rid), '--jq', '.[] | [.id, .name, .size, .state] | @tsv').splitlines():
            aid, name, size, state = row.split('\t'); out[name] = (int(aid), int(size), state)
        self._ids[tag] = out; return out
    def assets(self, tag): return {n: s for n, (_, s, state) in self._list(tag).items() if state == 'uploaded'}
    def read(self, tag, name):
        url = 'https://github.com/%s/releases/download/%s/%s' % (self.repo, tag, name)
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'daanvia-publish'}), timeout=120) as r: return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404: return None
            raise
    def upload(self, tag, items):
        stale = self._list(tag)
        for _, name in items:  # an upload that broke off leaves an asset that is not "uploaded"
            if name in stale and stale[name][2] != 'uploaded': self.gh('api', '-X', 'DELETE', 'repos/%s/releases/assets/%d' % (self.repo, stale[name][0]))
        with tempfile.TemporaryDirectory() as tmp:
            paths = []
            for path, name in items:
                if pathlib.Path(path).name == name: paths.append(str(path))
                else: q = pathlib.Path(tmp) / name; shutil.copyfile(path, q); paths.append(str(q))
            for i in range(0, len(paths), 20): self.gh('release', 'upload', tag, *paths[i:i + 20], '-R', self.repo, capture=False)
    def _id(self, tag, name):
        if name not in self._ids.get(tag, {}): self._list(tag)
        return self._ids[tag][name][0]
    def delete(self, tag, name): self.gh('api', '-X', 'DELETE', 'repos/%s/releases/assets/%d' % (self.repo, self._id(tag, name))); self._ids.pop(tag, None)
    def rename(self, tag, old, new): self.gh('api', '-X', 'PATCH', 'repos/%s/releases/assets/%d' % (self.repo, self._id(tag, old)), '-f', 'name=' + new); self._ids.pop(tag, None)
    def drop(self, tag): self.gh('release', 'delete', tag, '-R', self.repo, '--cleanup-tag', '--yes')


def repo_of(spec):
    if spec.startswith('dir:'): return DirRepo(spec[4:])
    if spec.startswith('gh:'): return GitHubRepo(spec[3:])
    raise SystemExit('--repo must be gh:OWNER/REPO or dir:PATH')


def load(dist): return json.loads((pathlib.Path(dist) / INDEX).read_text(encoding='utf-8'))
def save(dist, index):
    tmp = pathlib.Path(dist) / (INDEX + '.part'); tmp.write_text(json.dumps(index, indent=1, ensure_ascii=False) + '\n', encoding='utf-8'); tmp.replace(pathlib.Path(dist) / INDEX)
def live_index(repo):
    raw = repo.read(HOME, POINTER); return json.loads(raw) if raw else None


def check_dist(dist, index):
    """Every file the index names is in DIST with its size and sha256, and is named after that sha256."""
    if not index.get('generation'): raise SystemExit('the index has no generation (built before map generations?)')
    for spec in index['cells'] + index['base']:
        data = (pathlib.Path(dist) / spec['file']).read_bytes()
        if len(data) != spec['bytes'] or hashlib.sha256(data).hexdigest() != spec['sha256']: raise SystemExit(spec['file'] + ' does not match the index')
        if not spec['file'].endswith('-%s.zip' % spec['sha256'][:12]): raise SystemExit(spec['file'] + ' is not named after its sha256')


def wanted(index, have):
    """(missing names, bytes to send); stops if a present name has another size."""
    missing = []
    for name, size in sizes_of(index).items():
        if name not in have: missing.append(name)
        elif have[name] != size: raise SystemExit('%s is uploaded with %d bytes, the index says %d' % (name, have[name], size))
    return missing, sum(sizes_of(index)[n] for n in missing)


def stale_releases(repo):
    """(generation releases cleanup may delete, what it keeps and why)."""
    live = live_index(repo)
    if not live: return [], ['no live generation yet: nothing to clean up']
    keep = {release_of(live['generation']): 'live'}
    prev = live.get('previous') or {}
    if prev.get('release'): keep.setdefault(prev['release'], 'previous')
    gens = sorted(t for t in repo.releases() if GEN.match(t))
    for t in gens:
        if GEN.match(t).group(1) > live['generation']: keep.setdefault(t, 'uploaded, not live yet')
    return [t for t in gens if t not in keep], ['%s (%s)' % (t, why) for t, why in sorted(keep.items())]


def plan(dist, repo):
    index = load(dist); check_dist(dist, index); live = live_index(repo); tag = release_of(index['generation'])
    missing, send = wanted(index, repo.assets(tag))
    gone, kept = stale_releases(repo)
    print('generation %s (OSM %s, built %s, Valhalla %s, package schema %s)' % (index['generation'], index.get('osm_timestamp') or '?', index['built'],
          index.get('engine', {}).get('version', '?'), index.get('package_schema')))
    print('live: %s' % (live['generation'] if live else 'none (apps read the unversioned maps/areas-index.json)'))
    print('upload to %s: %d of %d files, %.1f MB' % (tag, len(missing), len(files_of(index)), send / 1e6))
    print('cleanup now would delete %s; keeps %s' % (gone or 'nothing', ', '.join(kept)))
    return {'missing': missing, 'bytes': send, 'cleanup': gone}


def upload(dist, repo):
    dist = pathlib.Path(dist); index = load(dist); check_dist(dist, index); live = live_index(repo); tag = release_of(index['generation'])
    if len(files_of(index)) + 1 > MAX_FILES: raise SystemExit('%d files do not fit one GitHub release (%d at most)' % (len(files_of(index)) + 1, MAX_FILES))
    want = dict(index, release=tag)
    if live and live['generation'] != index['generation']: want['previous'] = {'generation': live['generation'], 'release': live['release']}
    if want != index: index = want; save(dist, index)
    if tag not in repo.releases():
        repo.create(tag, 'Daanvia map data, generation %s: OpenStreetMap data of %s, Valhalla %s. Downloaded by the app; not an app release.'
                    % (index['generation'], index.get('osm_timestamp') or '?', index.get('engine', {}).get('version', '?')))
    missing, send = wanted(index, repo.assets(tag))
    print('uploading %d files, %.1f MB to %s' % (len(missing), send / 1e6, tag), flush=True)
    repo.upload(tag, [(dist / n, n) for n in missing])
    have = repo.assets(tag); still, _ = wanted(index, have)
    if still: raise SystemExit('still missing after upload: %s' % still[:5])
    raw = (dist / INDEX).read_bytes()
    if INDEX in have and repo.read(tag, INDEX) != raw: raise SystemExit('%s/%s is already uploaded with other contents' % (tag, INDEX))
    if INDEX not in have: repo.upload(tag, [(dist / INDEX, INDEX)])
    print('uploaded %s; not live until switch' % tag)


def switch(dist, repo, check=3):
    dist = pathlib.Path(dist); index = load(dist); raw = (dist / INDEX).read_bytes(); tag = index.get('release', '')
    if tag != release_of(index['generation']) or repo.read(tag, INDEX) != raw: raise SystemExit('run upload first')
    missing, _ = wanted(index, repo.assets(tag))
    if missing: raise SystemExit('not uploaded yet: %d files, e.g. %s' % (len(missing), missing[:3]))
    live = live_index(repo)
    if live and live['generation'] == index['generation']: print('already live'); return
    if (live or {}).get('generation') != index.get('previous', {}).get('generation'): raise SystemExit('the live generation changed since upload; run upload again')
    sha = sha_of(index)
    for name in random.sample(files_of(index), min(check, len(files_of(index)))):
        if hashlib.sha256(repo.read(tag, name) or b'').hexdigest() != sha[name]: raise SystemExit('%s/%s does not match its sha256' % (tag, name))
    have = repo.assets(HOME)
    if NEXT in have: repo.delete(HOME, NEXT)
    repo.upload(HOME, [(dist / INDEX, NEXT)])
    if repo.read(HOME, NEXT) != raw: raise SystemExit(NEXT + ' did not upload intact; nothing switched')
    if POINTER in have: repo.delete(HOME, POINTER)
    repo.rename(HOME, NEXT, POINTER)
    if repo.read(HOME, POINTER) != raw: raise SystemExit(POINTER + ' does not read back as the new generation: check the maps release now')
    print('live: %s' % index['generation'])


def cleanup(repo, yes=False):
    gone, kept = stale_releases(repo)
    print('keeping %s' % ', '.join(kept))
    print('%s %s' % ('deleting' if yes else 'would delete (add --yes)', gone or 'nothing'), flush=True)
    if yes:
        for t in gone: repo.drop(t)
    return gone


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('dist'); ap.add_argument('step', nargs='?', default='plan', choices=('plan', 'upload', 'switch', 'cleanup'))
    ap.add_argument('--repo', default='gh:danrave1234/daanvia'); ap.add_argument('--check', type=int, default=3); ap.add_argument('--yes', action='store_true')
    a = ap.parse_args(argv); repo = repo_of(a.repo)
    if a.step == 'plan': plan(a.dist, repo)
    elif a.step == 'upload': upload(a.dist, repo)
    elif a.step == 'switch': switch(a.dist, repo, a.check)
    else: cleanup(repo, a.yes)


if __name__ == '__main__':
    sys.exit(main())

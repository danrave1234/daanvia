"""Tests for publish_maps.py on a folder standing in for the repository's releases (no network, no extra packages).

usage: python tools/test_publish_maps.py
"""
import contextlib, hashlib, io, json, pathlib, sys, tempfile, unittest
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import publish_maps as pm


def build(root, gen, cells, base=('base_l1_1',)):
    """A fake build: cells is {cell id: contents}; returns its dist folder."""
    d = pathlib.Path(root) / gen; d.mkdir(parents=True)
    def put(stem, data):
        sha = hashlib.sha256(data).hexdigest(); name = '%s-%s.zip' % (stem, sha[:12]); (d / name).write_bytes(data)
        return {'file': name, 'bytes': len(data), 'sha256': sha}
    bases = [put(b, ('tiles ' + b).encode()) for b in base]
    entries = [{'id': cid, 'box': [0, 0, 1, 1], 'needs': [b['file'] for b in bases], 'label': '', 'roads_bytes': 1, **put(cid, text.encode())} for cid, text in cells.items()]
    (d / 'areas-index.json').write_text(json.dumps({'version': 2, 'package_schema': 3, 'generation': gen, 'built': gen, 'osm_timestamp': 'x',
                                                   'engine': {'name': 'valhalla', 'version': '3.9.0'}, 'cells': entries, 'base': bases}), encoding='utf-8')
    return d


def quiet(fn, *a, **k):
    with contextlib.redirect_stdout(io.StringIO()): return fn(*a, **k)


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.tmp.name); self.repo = pm.DirRepo(self.root / 'repo')
        # what is published today, for app versions that only read maps/areas-index.json
        self.repo.create('maps', ''); legacy = self.root / 'repo' / 'maps'
        for n in ('cell_a.zip', 'base_l1_1.zip'): (legacy / n).write_bytes(b'legacy ' + n.encode())
        self.legacy = json.dumps({'version': 2, 'built': '2026-10-20T15:54:23+00:00', 'cells': [{'id': 'cell_a', 'file': 'cell_a.zip', 'bytes': 17}], 'base': [{'file': 'base_l1_1.zip', 'bytes': 20}]})
        (legacy / 'areas-index.json').write_text(self.legacy)
    def tearDown(self): self.tmp.cleanup()
    def live(self): return json.loads(self.repo.read('maps', 'areas-current.json'))
    def downloadable(self, index):
        """What a phone holding this index can still fetch: every file in index["release"] with its size."""
        have = self.repo.assets(index.get('release', 'maps')); return all(have.get(n) == s for n, s in pm.sizes_of(index).items())
    def untouched_legacy(self):
        self.assertEqual(self.legacy, self.repo.read('maps', 'areas-index.json').decode())
        self.assertTrue(self.downloadable(json.loads(self.legacy)))

    def test_generations_replace_each_other_without_breaking_older_downloads(self):
        g1 = build(self.root, 'g20261101T000000Z', {'cell_a': 'roads a1', 'cell_b': 'roads b1'})
        quiet(pm.upload, g1, self.repo)
        self.assertIsNone(self.repo.read('maps', 'areas-current.json'), 'upload must not change what phones see')
        quiet(pm.switch, g1, self.repo)
        first = self.live(); self.assertEqual('maps-g20261101T000000Z', first['release']); self.assertNotIn('previous', first)
        self.assertTrue(self.downloadable(first)); self.untouched_legacy()

        g2 = build(self.root, 'g20261108T000000Z', {'cell_a': 'roads a1', 'cell_b': 'roads b2'})
        self.assertEqual(3, len(quiet(pm.plan, g2, self.repo)['missing']))
        quiet(pm.upload, g2, self.repo)
        self.assertEqual([], quiet(pm.cleanup, self.repo, True), 'nothing goes while g1 is live')
        quiet(pm.switch, g2, self.repo)
        self.assertEqual({'generation': 'g20261101T000000Z', 'release': 'maps-g20261101T000000Z'}, self.live()['previous'])
        self.assertEqual([], quiet(pm.cleanup, self.repo, True), 'the previous generation stays after the switch')
        self.assertTrue(self.downloadable(first), 'a phone that read the old index must still finish')

        g3 = build(self.root, 'g20261115T000000Z', {'cell_a': 'roads a3', 'cell_b': 'roads b3'})
        quiet(pm.upload, g3, self.repo); quiet(pm.switch, g3, self.repo)
        self.assertEqual(['maps-g20261101T000000Z'], quiet(pm.cleanup, self.repo, True), 'the generation before the previous one goes')
        self.assertEqual({'maps', 'maps-g20261108T000000Z', 'maps-g20261115T000000Z'}, self.repo.releases())
        self.assertTrue(self.downloadable(self.live())); self.untouched_legacy()

    def test_uploaded_generations_survive_cleanup_until_switched(self):
        for g in ('g20261101T000000Z', 'g20261108T000000Z'):
            d = build(self.root, g, {'cell_a': g}); quiet(pm.upload, d, self.repo); quiet(pm.switch, d, self.repo)
        pending = build(self.root, 'g20261115T000000Z', {'cell_a': 'next'}); quiet(pm.upload, pending, self.repo)
        self.assertEqual([], quiet(pm.cleanup, self.repo, True))
        self.assertIn('maps-g20261115T000000Z', self.repo.releases())

    def test_a_reused_name_with_other_bytes_stops_the_upload(self):
        g1 = build(self.root, 'g20261101T000000Z', {'cell_a': 'roads a1'})
        name = json.loads((g1 / 'areas-index.json').read_text())['cells'][0]['file']
        self.repo.create('maps-g20261101T000000Z', ''); (self.root / 'repo' / 'maps-g20261101T000000Z' / name).write_bytes(b'something else entirely')
        with self.assertRaises(SystemExit): quiet(pm.upload, g1, self.repo)

    def test_switch_needs_a_complete_upload(self):
        g1 = build(self.root, 'g20261101T000000Z', {'cell_a': 'roads a1'})
        with self.assertRaises(SystemExit): quiet(pm.switch, g1, self.repo)
        quiet(pm.upload, g1, self.repo)
        (self.root / 'repo' / 'maps-g20261101T000000Z' / json.loads((g1 / 'areas-index.json').read_text())['cells'][0]['file']).unlink()
        with self.assertRaises(SystemExit): quiet(pm.switch, g1, self.repo)
        self.assertIsNone(self.repo.read('maps', 'areas-current.json'))

    def test_a_generation_must_fit_one_release(self):
        g1 = build(self.root, 'g20261101T000000Z', {'cell_%d' % i: str(i) for i in range(1000)})
        with self.assertRaises(SystemExit): quiet(pm.upload, g1, self.repo)
        self.assertEqual({'maps'}, self.repo.releases())

    def test_unversioned_builds_are_refused(self):
        d = self.root / 'old'; d.mkdir(); (d / 'cell_a.zip').write_bytes(b'x')
        (d / 'areas-index.json').write_text(json.dumps({'built': 'x', 'cells': [{'id': 'a', 'file': 'cell_a.zip', 'bytes': 1, 'sha256': hashlib.sha256(b'x').hexdigest()}], 'base': []}))
        with self.assertRaises(SystemExit): quiet(pm.plan, d, self.repo)


if __name__ == '__main__':
    unittest.main()

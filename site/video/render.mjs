// Renders every composition to out/ at 1080p, then makes 720p web copies and posters in ../web/public/media.
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
const reuse = process.argv.includes('--reuse');
const ids = process.argv.slice(2).filter((a) => !a.startsWith('--')).length ? process.argv.slice(2).filter((a) => !a.startsWith('--')) : ['promo', 'tut-install', 'tut-area', 'tut-bike'];
const media = '../web/public/media';
for (const id of ids) {
  if (!(reuse && existsSync(`out/${id}.mp4`))) execFileSync('npx', ['remotion', 'render', 'src/index.ts', id, `out/${id}.mp4`, '--codec=h264', '--crf=18', '--concurrency=3', '--timeout=120000'], { stdio: 'inherit', shell: true });
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-i', `out/${id}.mp4`, '-vf', 'scale=1280:-2:flags=lanczos', '-c:v', 'libx264', '-preset', 'slow', '-crf', '27', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-an', `${media}/${id}.mp4`], { stdio: 'inherit' });
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-ss', id === 'promo' ? '1.5' : '9', '-i', `out/${id}.mp4`, '-frames:v', '1', '-vf', 'scale=1280:-2', '-c:v', 'libwebp', '-quality', '80', `${media}/${id}.webp`], { stdio: 'inherit' });
  console.log('done', id);
}

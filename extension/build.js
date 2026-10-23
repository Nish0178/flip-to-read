import * as esbuild from 'esbuild';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

async function build() {
  console.log('🚀 Building Flip to Read Chrome/Edge Extension...');
  const distDir = path.join(__dirname, 'dist');

  // Clean / recreate dist directory
  if (fs.existsSync(distDir)) {
    fs.rmSync(distDir, { recursive: true, force: true });
  }
  fs.mkdirSync(distDir, { recursive: true });

  // 1. Bundle TypeScript entrypoints with esbuild
  const entryPoints = [
    { in: path.join(__dirname, 'src', 'background', 'service-worker.ts'), out: 'background/service-worker' },
    { in: path.join(__dirname, 'src', 'content', 'pdf-detector.ts'), out: 'content/pdf-detector' },
    { in: path.join(__dirname, 'src', 'popup', 'popup.ts'), out: 'popup/popup' },
    { in: path.join(__dirname, 'src', 'reader', 'reader.ts'), out: 'reader/reader' },
    { in: path.join(__dirname, 'src', 'options', 'options.ts'), out: 'options/options' }
  ];

  await esbuild.build({
    entryPoints,
    bundle: true,
    format: 'esm',
    target: 'es2022',
    outdir: distDir,
    sourcemap: false,
    minify: false
  });
  console.log('✓ TypeScript compiled successfully.');

  // 2. Copy manifest.json
  fs.copyFileSync(path.join(__dirname, 'manifest.json'), path.join(distDir, 'manifest.json'));

  // 3. Copy icons
  const iconSrcDir = path.join(__dirname, 'public', 'icons');
  const iconDestDir = path.join(distDir, 'icons');
  fs.mkdirSync(iconDestDir, { recursive: true });
  if (fs.existsSync(iconSrcDir)) {
    for (const file of fs.readdirSync(iconSrcDir)) {
      fs.copyFileSync(path.join(iconSrcDir, file), path.join(iconDestDir, file));
    }
  }

  // 4. Copy HTML & CSS files
  const htmlCssPairs = [
    { src: 'src/popup/popup.html', dest: 'popup/popup.html' },
    { src: 'src/popup/popup.css', dest: 'popup/popup.css' },
    { src: 'src/reader/reader.html', dest: 'reader/reader.html' },
    { src: 'src/reader/reader.css', dest: 'reader/reader.css' },
    { src: 'src/options/options.html', dest: 'options/options.html' },
    { src: 'src/options/options.css', dest: 'options/options.css' }
  ];

  for (const item of htmlCssPairs) {
    const srcPath = path.join(__dirname, item.src);
    const destPath = path.join(distDir, item.dest);
    fs.mkdirSync(path.dirname(destPath), { recursive: true });
    fs.copyFileSync(srcPath, destPath);
  }

  // 5. Copy shared page-flip.browser.js library
  const libDestDir = path.join(distDir, 'lib');
  fs.mkdirSync(libDestDir, { recursive: true });
  const sharedPageFlip = path.join(__dirname, '..', 'java-app', 'src', 'main', 'resources', 'static', 'page-flip.browser.js');
  if (fs.existsSync(sharedPageFlip)) {
    fs.copyFileSync(sharedPageFlip, path.join(libDestDir, 'page-flip.browser.js'));
    console.log('✓ Shared PageFlip engine copied.');
  }

  console.log('🎉 Extension build completed! Output directory: extension/dist');
}

build().catch((err) => {
  console.error('❌ Build failed:', err);
  process.exit(1);
});

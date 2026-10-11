// SPDX-License-Identifier: GPL-3.0-only
// Run with Node.js and sharp installed; see README.md in this directory.
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');

const root = path.resolve(__dirname, '../..');
const res = path.join(root, 'app/src/main/res');
const svgHeader = '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">';
const vectorHeader = '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp" android:height="108dp"\n    android:viewportWidth="108" android:viewportHeight="108">';

async function main() {
  const source = await fs.readFile(path.join(__dirname, 'icon_source.svg'), 'utf8');
  const background = source.match(/<path id="background"[^>]+\/>/)[0];
  const foreground = source.match(/<g id="foreground">([\s\S]*?)<\/g>/)[1].trim();
  function vector(paths) {
    const elements = [...paths.matchAll(/<path\b([^>]+)\/>/g)].map(([, attrs]) => {
      const fill = attrs.match(/\bfill="([^"]+)"/)[1];
      const d = attrs.match(/\bd="([^"]+)"/)[1];
      const rule = attrs.includes('fill-rule="evenodd"') ? '\n        android:fillType="evenOdd"' : '';
      return `    <path android:fillColor="${fill}"${rule}\n        android:pathData="${d}"/>`;
    });
    return `<?xml version="1.0" encoding="utf-8"?>\n${vectorHeader}\n${elements.join('\n')}\n</vector>\n`;
  }
  await fs.writeFile(path.join(res, 'drawable/ic_launcher_foreground.xml'), vector(foreground));
  await fs.writeFile(path.join(res, 'drawable-v24/ic_launcher_background.xml'), vector(background));
  await fs.writeFile(path.join(__dirname, 'foreground_layer_export.svg'), `${svgHeader}\n${foreground}\n</svg>\n`);
  await fs.writeFile(path.join(__dirname, 'background_layer_export.svg'), `${svgHeader}\n${background}\n</svg>\n`);

  // Android masks a 108dp adaptive layer down to its central 72dp viewport.
  // Use that same crop for legacy and store icons, keeping the mark's size consistent.
  function iconSvg(shape) {
    const clip = shape === 'round' ? '<circle cx="54" cy="54" r="36"/>'
      : shape === 'square' ? '<rect x="18" y="18" width="72" height="72"/>'
      : '<rect x="18" y="18" width="72" height="72" rx="16"/>';
    return `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72"><defs><clipPath id="mask">${clip}</clipPath></defs><g clip-path="url(#mask)">${background}${foreground}</g></svg>`;
  }
  async function png(shape, size, destination) {
    await sharp(Buffer.from(iconSvg(shape)), { density: 384 }).resize(size, size).png().toFile(destination);
  }
  for (const [density, size] of Object.entries({ mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 })) {
    await png('rounded', size, path.join(res, `mipmap-${density}/ic_launcher.png`));
    await png('round', size, path.join(res, `mipmap-${density}/ic_launcher_round.png`));
  }
  await png('square', 512, path.join(root, 'fastlane/metadata/android/en-US/images/icon.png'));
  await png('rounded', 512, path.join(__dirname, 'youboard-icon.png'));
}

main().catch(error => { console.error(error); process.exitCode = 1; });

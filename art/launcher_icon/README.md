# YOUBoard icon

The icon uses a single mint keycap with a Y cutout, an ivory spacebar cue,
and a deep teal background. The
original artwork is in `icon_source.svg`; every shape is a path, with no font
dependency. The mark fits within Android's central 66dp adaptive-icon safe zone.
The foreground also provides the silhouette for Android's themed icons.

`generate.cjs` exports the adaptive VectorDrawables, separate SVG layers, all
five legacy launcher densities (rounded square and circle), the 512px store
icon, and `youboard-icon.png` for preview. The legacy and store exports use the
central 72dp crop of the 108dp adaptive layers.

To regenerate, use Node.js with the `sharp` package available:

```powershell
npm install --prefix "$env:TEMP/youboard-icon-tools" sharp
$env:NODE_PATH = "$env:TEMP/youboard-icon-tools/node_modules"
node art/launcher_icon/generate.cjs
```

This YOUBoard artwork is licensed under GPL-3.0-only, like the application.
The former HeliBoard icon and its attribution remain in Git history.

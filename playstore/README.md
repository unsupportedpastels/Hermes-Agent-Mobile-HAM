# Play Store listing assets

These are **Google Play Console** assets, not build artifacts. They are uploaded
manually in the Play Console and are **not** produced or published by the
`release.yml` workflow.

## `ic_launcher-playstore-512.png`

- **What:** The Play Store "hi-res icon" — 512×512, 32-bit PNG (RGBA).
- **Where it goes:** Play Console → your app → **Grow → Store presence → Main
  store listing → App icon**. (Also part of the initial-listing checklist in the
  release plan's Prerequisite P6.)
- **How it was derived:** A faithful LANCZOS downscale of the master artwork
  `app/src/main/res/drawable-nodpi/ham_launcher_art.png` (1254×1254). It is a
  straight resize — *not* a regeneration — so it matches the installed launcher
  icon exactly. Do not hand-edit; regenerate from the master if the brand art
  changes.

To regenerate:

```bash
python3 - <<'PY'
from PIL import Image
Image.open('app/src/main/res/drawable-nodpi/ham_launcher_art.png') \
    .convert('RGBA').resize((512, 512), Image.LANCZOS) \
    .save('playstore/ic_launcher-playstore-512.png')
PY
```

## Related launcher icon files (in `app/src/main/res/`)

- `mipmap-anydpi/ic_launcher.xml`, `ic_launcher_round.xml` — adaptive icon
  (background + foreground + **monochrome** themed-icon layer).
- `drawable/ic_launcher_foreground.xml`, `ic_launcher_background.xml` — adaptive
  foreground/background.
- `drawable-nodpi/ic_launcher_monochrome.png` — Android 13+ themed-icon
  silhouette, sized inside the 66dp safe zone.
- `mipmap-*/ic_launcher*.webp` — legacy raster fallbacks (mdpi→xxxhdpi).

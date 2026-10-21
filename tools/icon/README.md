# Launcher icon generator

`emanata_icon.py` draws the launcher icon (a comic burst with a "!") from a set of parameters and writes it as Android adaptive-icon layers. Python 3 standard library only; contact sheets also need ImageMagick (`magick`) on PATH.

Regenerate after changing a parameter:

```
py -3 tools/icon/emanata_icon.py --previews
```

- `tools/icon/out/res/` holds the layers: copy its `drawable*/` folders over `app/src/main/res/`.
- `tools/icon/previews/` holds contact sheets (circle and squircle masks, color and themed, 48–192 px) and `icon_512_fullbleed.png`, which is also `screenshots/icon.png`.
- `--help` lists every parameter. They are flags of the same name, e.g. `--tail-reach 35.5 --long-reach 28`.

Low densities get fewer shards (`SHARDS_BY_DENSITY`), so `drawable-ldpi`, `-mdpi` and `-hdpi` carry their own layers.

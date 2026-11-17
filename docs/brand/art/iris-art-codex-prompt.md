Generate the Iris art batch with your built-in image generation (no external image APIs, no scripts that draw the images).

Specification (read it completely first): /home/tim/github/itmitalles-software/iris/docs/brand/art/iris-art-spec.md
Reference images (attach/consult the ones each item names): /home/tim/reviews-local/iris-art-2026-11-17/refs/
Output folder: /home/tim/reviews-local/iris-art-2026-11-17/codex-out/

Rules:
1. Generate exactly these 7 items, one image each, in this fixed order: 01-web-hero, 02-web-feature-chat, 06-web-feature-automation, 08-web-og, 09-app-landing, 10-app-empty-chat, 14-logo-gold-trippy.
2. Save every native output unchanged (no crop, resize, recolour, re-encode or upscaling) as `<number>-<item id>.png` in the output folder, e.g. `01-web-hero.png`. If the native file is not PNG, keep its bytes and use its real extension instead.
3. After each image, append one line to `codex-out/PROVENANCE.md`: number, item id, the original generated file path, the reference files you used, the exact prompt text you sent to the image generator.
4. Items 01–10 are black ink engravings on plain white paper only; item 14 is the gold emblem on pure black. Follow the shared style and the per-item content of the spec exactly. No text, letters or watermarks in any image (item 14: only the single letter I).
5. Keep Iris consistent across items (same face, hair knot with diadem, small wings in the hair, chiton, back wings where shoulders are visible).
6. If you are interrupted, resume at the first missing number; never regenerate or overwrite an existing file.
7. Do not edit the repository. When done, list the saved files with their pixel sizes.

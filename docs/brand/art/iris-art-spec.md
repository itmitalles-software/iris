# Iris art batch (2026-11-17)

Replaces the Apollo-specific illustrations (Apollo bust, lyre, sun chariot,
gold "A" logo) after the rename from Apollo to Iris. The generic classical
motifs (temple, hand, seated figure, colonnade, serpent column, laurel,
broken column) stay.

Iris is the Greek goddess of the rainbow and messenger of the gods. Her
attributes: feathered wings (on the shoulders, sometimes small wings in the
hair), a rainbow arc, a pitcher (oinochoe), flowing chiton. Do **not** draw a
caduceus/kerykeion (staff with two snakes), Hermes, winged sandals as the main
motif, or any Nous Research / Hermes Agent logo or character.

Native outputs are produced by the owner's local Codex and saved unchanged.
`scripts/brand/process_art.py <codex-out>` derives the app and website files.

- Output folder: `/home/tim/reviews-local/iris-art-2026-11-17/codex-out/`
- File name: `<number>-<item id>.png`, exactly as listed below
- References: `/home/tim/reviews-local/iris-art-2026-11-17/refs/`
- Provenance: `/home/tim/reviews-local/iris-art-2026-11-17/codex-out/PROVENANCE.md`

## Shared style (items 01–10: engravings)

- Classical copperplate / banknote engraving: fine parallel hatching,
  cross-hatching and stippling, with op-art linework (radiating fine lines,
  concentric arcs, undulating parallel lines, small dots on thin orbit lines).
  Match the line density and mood of the style references
  `style-01-old-hero.png`, `style-03-temple.png`, `style-09-old-app-landing.png`.
- **Pure black ink on plain white paper.** No colour, no grey wash, no paper
  texture, no vignette, no border, no frame, no text, no letters, no signature,
  no watermark. The white is later turned into transparency and the ink into
  purple, so every grey tone must come from line density.
- Iris looks the same in every item: a young woman with calm classical
  features, hair gathered in a loose knot with a thin diadem, two small
  feathered wings in the hair above the ears, a draped chiton fastened on
  one shoulder, large feathered wings on her back where the figure shows the
  shoulders. Rainbow = a set of 5–7 concentric arcs drawn as fine line bands
  (different hatching density per band, never colour).
- The motif is centred, fully visible, with generous empty white margin,
  unless the item says otherwise.

## Items (generate in this order)

| # | Item id | Size / aspect | Content |
| --- | --- | --- | --- |
| 01 | `web-hero` | portrait 4:5, at least 1400 × 1750 | Website hero. Half figure of Iris in three-quarter view looking to the right, large feathered wings half spread behind her shoulders. Behind her head a rainbow halo of concentric fine-line arcs with radiating rays. With both hands she holds glowing threads of light that connect small orbs into a network/constellation in front of her chest (message routes). Fills the frame like `style-01-old-hero.png`, which it replaces; edge-to-edge composition allowed. |
| 02 | `web-feature-chat` | square 1:1, at least 1200 × 1200 | Feature "chat". A classical oinochoe (pitcher) tilted in mid-air, pouring a stream that turns into many undulating parallel op-art wave lines flowing left and right across the frame like a conversation. A pair of small feathered wings on the pitcher's shoulder. Replaces the lyre in `style-02-old-chat.png`; keep its wave-line density. |
| 06 | `web-feature-automation` | square 1:1, at least 1200 × 1200 | Feature "automation". A rainbow bridge (concentric fine-line arcs) spanning from cloud bank to cloud bank across the frame; at its foot a large classical clock dial / orrery with hands and fine ticks; a small winged figure of Iris gliding along the arc. Replaces the sun chariot in `style-06-old-automation.png`. |
| 08 | `web-og` | landscape 1200 × 630 (1.905:1) | Social preview. Left 45 % plain white (text is placed there later). Right half: profile medallion of Iris (head and neck in profile facing left, small wings in the hair) inside a circular frame of fine hatching, flanked by two large feathered wings instead of a laurel wreath, with radiating fine rays and a thin rainbow arc above. Replaces `style-08-old-og.png` (shown inverted). |
| 09 | `app-landing` | portrait 4:5, at least 1000 × 1250 | App start screen. Marble bust of Iris on a round plinth, front view, eyes calm, small wings in the hair, behind her a rainbow halo of concentric fine-line arcs plus a fine sunburst with dots on thin lines. Isolated on white with wide margin, like `style-09-old-app-landing.png`, which it replaces. |
| 10 | `app-empty-chat` | square 1:1, at least 1000 × 1000 | App empty chat state. Small isolated emblem: a classical oinochoe (pitcher) with a pair of small feathered wings, under a thin rainbow arc of fine lines, a few radiating rays. Simple and readable at 180 px. Isolated on white with wide margin, like `style-10-old-app-empty-chat.png` (lyre), which it replaces. |

## Gold logo (colour, generated last)

| # | Item id | Size / aspect | Content |
| --- | --- | --- | --- |
| 14 | `logo-gold-trippy` | square 1:1, at least 1000 × 1000 | Engraved metallic gold emblem on pure black, same rendering style as `style-14-old-logo-gold.png` (gold "A" with sun crown), but with the **geometry of `geometry-14-new-iris-emblem.png`**: a tall classical serif capital **I** that rises above a gold rainbow of three concentric half-ring bands (outer band brightest, inner band deepest gold), fine rays of light behind the I inside the inner band, a thin technical dial ring with ticks, subtle undulating halo rings. Gold `#D4AF72` with highlights `#F2DDAE` and shadows `#A47C45`, fine engraved line texture on the gold. The letter must be an unmistakable I, no other letters or text. Black background must be pure `#000000` with no frame. |

The numbers are fixed: website files keep their published names
(`01-…`, `02-…`, `06-…`, `08-…`), and `process_art.py` treats numbers ≥ 14 as
gold items that keep their colours. Generation order: 01, 02, 06, 08, 09, 10, 14.

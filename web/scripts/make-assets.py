"""Regenerate the site's fonts and icons from the Android app's own files.

The website copies the app's brand instead of re-creating it, so these come
straight from the app module:

  fonts  core/ui/src/main/res/font/*.ttf  ->  web/src/assets/fonts/*.woff2
  icons  app/src/main/res/drawable/ic_launcher_foreground.png
                                           ->  web/public/favicon.ico,
                                               icon-192.png, apple-touch-icon.png,
                                               web/src/assets/brand/vinyl.png

Run it from anywhere after the app's fonts or icon change, then commit the output:

  pip install fonttools brotli pillow
  python web/scripts/make-assets.py

Screenshots aren't handled here: copy the .webp files into
web/src/assets/screenshots/ by hand (see web/README.md).
"""

from pathlib import Path

from fontTools import subset
from PIL import Image

REPO = Path(__file__).resolve().parents[2]
WEB = REPO / "web"

# --- Fonts -------------------------------------------------------------------

FONT_SRC = REPO / "core/ui/src/main/res/font"
FONT_DST = WEB / "src/assets/fonts"

# Google Fonts' "latin" range, plus arrows (U+2190-2199) for the "->" in links.
LATIN = (
    "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,"
    "U+2000-206F,U+20AC,U+2122,U+2190-2199,U+2212,U+2215,U+FEFF,U+FFFD"
)

FONTS = {
    "inter_regular.ttf": "inter-400.woff2",
    "inter_medium.ttf": "inter-500.woff2",
    "inter_semibold.ttf": "inter-600.woff2",
    # The app's "space_grotesk_semibold.ttf" is really the Medium (500) cut,
    # so the site names it, and uses it, as 500.
    "space_grotesk_semibold.ttf": "space-grotesk-500.woff2",
    "space_grotesk_bold.ttf": "space-grotesk-700.woff2",
}


def make_fonts() -> None:
    FONT_DST.mkdir(parents=True, exist_ok=True)
    for src, dst in FONTS.items():
        out = FONT_DST / dst
        subset.main([
            str(FONT_SRC / src),
            f"--unicodes={LATIN}",
            "--layout-features+=tnum,case",  # defaults, plus tabular figures and case-aware punctuation
            "--name-IDs=*",                  # keeps the copyright and OFL licence inside each file
            "--flavor=woff2",
            f"--output-file={out}",
        ])
        print(f"{out.relative_to(REPO)}: {out.stat().st_size:,} bytes")


# --- Icons -------------------------------------------------------------------

ICON_SRC = REPO / "app/src/main/res/drawable/ic_launcher_foreground.png"
ICON_DST = WEB / "public"
BRAND_DST = WEB / "src/assets/brand"
LAUNCHER_BACKGROUND = (17, 17, 17, 255)  # #111111, app/src/main/res/drawable/ic_launcher_background.xml


def vinyl() -> Image.Image:
    """The record from the adaptive icon's foreground, cropped to its edges."""
    image = Image.open(ICON_SRC).convert("RGBA")
    alpha = image.getchannel("A").point(lambda v: 255 if v > 10 else 0)
    return image.crop(alpha.getbbox())


def on_background(record: Image.Image, size: int, fill: float) -> Image.Image:
    """The record centred on the launcher's background, `fill` of the width."""
    canvas = Image.new("RGBA", (size, size), LAUNCHER_BACKGROUND)
    inner = round(size * fill)
    scaled = record.resize((inner, inner), Image.Resampling.LANCZOS)
    offset = (size - inner) // 2
    canvas.alpha_composite(scaled, (offset, offset))
    return canvas


def make_icons() -> None:
    record = vinyl()
    BRAND_DST.mkdir(parents=True, exist_ok=True)
    # The header and footer logo; Astro resizes it at build time.
    record.save(BRAND_DST / "vinyl.png", optimize=True)
    record.save(ICON_DST / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
    record.resize((192, 192), Image.Resampling.LANCZOS).save(ICON_DST / "icon-192.png", optimize=True)
    # iOS fills transparency with black, so the touch icon gets the launcher's background.
    on_background(record, 180, 0.8).convert("RGB").save(ICON_DST / "apple-touch-icon.png", optimize=True)
    for path in (ICON_DST / "favicon.ico", ICON_DST / "icon-192.png",
                 ICON_DST / "apple-touch-icon.png", BRAND_DST / "vinyl.png"):
        print(f"{path.relative_to(REPO)}: {path.stat().st_size:,} bytes")


if __name__ == "__main__":
    make_fonts()
    make_icons()

"""
Extract 'Stash' from Bungee Shade as SVG paths and build an Android VectorDrawable.

Bungee Shade renders characters as compound glyphs — the "shadow" behind each letter
is a separate sub-path. When rendered as text, Android's rasterizer loses shadow
detail at small sizes. By extracting the glyphs as vector paths ONCE, we get crisp
rendering at any size because it's pure geometry.
"""
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
import sys
import xml.etree.ElementTree as ET
import re

FONT_PATH = "C:/Users/theno/Projects/MP3APK/design/logo-concepts/BungeeShade.ttf"
TEXT = "Stash"
OUT_SVG_DARK = "C:/Users/theno/Projects/MP3APK/design/logo-concepts/wordmark-bungee-shade.svg"
OUT_SVG_LIGHT = "C:/Users/theno/Projects/MP3APK/design/logo-concepts/wordmark-bungee-shade-light.svg"
OUT_VECTOR_XML_DARK = "C:/Users/theno/Projects/MP3APK/design/logo-concepts/wordmark_stash_dark.xml"
OUT_VECTOR_XML_LIGHT = "C:/Users/theno/Projects/MP3APK/design/logo-concepts/wordmark_stash_light.xml"

# Colors for DARK backgrounds (SVG + in-app dark theme):
#   S = bright purple, t = white, a = white, s = bright purple, h = bright purple
COLORS_DARK = ["#C084FC", "#FFFFFF", "#FFFFFF", "#C084FC", "#C084FC"]

# Colors for LIGHT backgrounds (Play Store + in-app light theme):
#   S = deep purple, t = near-black purple, a = near-black purple, s = deep purple, h = deep purple
COLORS_LIGHT = ["#7c3aed", "#1e0742", "#1e0742", "#7c3aed", "#7c3aed"]


def extract_glyphs(font_path, text):
    """Extract each character as (glyph_name, svg_path_data, advance_width, bbox)."""
    font = TTFont(font_path)
    cmap = font.getBestCmap()
    glyph_set = font.getGlyphSet()
    hmtx = font["hmtx"].metrics
    units_per_em = font["head"].unitsPerEm
    ascent = font["hhea"].ascent
    descent = font["hhea"].descent

    glyphs = []
    for char in text:
        code_point = ord(char)
        if code_point not in cmap:
            print(f"Warning: '{char}' not in cmap", file=sys.stderr)
            continue
        glyph_name = cmap[code_point]
        glyph = glyph_set[glyph_name]

        pen = SVGPathPen(glyph_set)
        glyph.draw(pen)
        path_data = pen.getCommands()

        advance_width, _ = hmtx.get(glyph_name, (0, 0))
        glyphs.append({
            "char": char,
            "name": glyph_name,
            "path": path_data,
            "advance": advance_width,
        })

    return glyphs, units_per_em, ascent, descent


def build_svg(glyphs, units_per_em, ascent, descent, colors):
    """
    Build an SVG with one <path> per character, colored per letter.

    Font glyphs use Y-up coordinates; SVG uses Y-down. Rather than parsing
    and flipping every path command (error-prone with V/H/A commands), we
    apply a group transform that flips the whole coordinate system once:
      translate(x_offset, ascent) scale(1, -1)
    This means a font-space point (x, y) lands at SVG (x_offset+x, ascent-y).

    @param colors  List of hex colors, one per glyph.
    """
    total_advance = sum(g["advance"] for g in glyphs)
    total_height = ascent - descent  # full line height

    svg_paths = []
    x_offset = 0
    for i, g in enumerate(glyphs):
        color = colors[i] if i < len(colors) else "#a855f7"
        svg_paths.append(
            f'  <g transform="translate({x_offset} {ascent}) scale(1 -1)">'
            f'<path d="{g["path"]}" fill="{color}"/></g>'
        )
        x_offset += g["advance"]

    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {total_advance} {total_height}" width="{total_advance}" height="{total_height}">
{chr(10).join(svg_paths)}
</svg>
'''
    return svg, total_advance, total_height


def build_vector_drawable(glyphs, units_per_em, ascent, descent, colors):
    """
    Build an Android VectorDrawable XML.

    Android <group> supports translate + scale, so we can use the same
    "flip by transform" approach as the SVG (scaleY=-1 flips Y-up to Y-down).

    @param colors List of hex colors, one per glyph.
    """
    total_advance = sum(g["advance"] for g in glyphs)
    total_height = ascent - descent

    # Scale viewport to a manageable width (Android VD numbers should be reasonable)
    target_width = 600.0
    scale = target_width / total_advance
    viewport_width = total_advance
    viewport_height = total_height
    vd_width = target_width
    vd_height = total_height * scale

    paths_xml = []
    x_offset = 0
    for i, g in enumerate(glyphs):
        color = colors[i] if i < len(colors) else "#C084FC"
        # Wrap in a group with translate+scale to flip Y-axis for this glyph.
        # Nested <group> is needed because android:scaleY alone pivots at origin;
        # we want translate applied FIRST (to position), then scale (to flip).
        # In Android VD, transforms apply outside-in: parent group translates,
        # child group flips Y so path data in font space renders correctly.
        paths_xml.append(
            f'''    <group android:translateX="{x_offset}" android:translateY="{ascent}">
        <group android:scaleY="-1">
            <path
                android:fillColor="{color}"
                android:pathData="{g["path"]}" />
        </group>
    </group>'''
        )
        x_offset += g["advance"]

    vd = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{vd_width:.0f}dp"
    android:height="{vd_height:.0f}dp"
    android:viewportWidth="{viewport_width}"
    android:viewportHeight="{viewport_height}">
{chr(10).join(paths_xml)}
</vector>
'''
    return vd


def main():
    print(f"Loading font: {FONT_PATH}")
    glyphs, upem, ascent, descent = extract_glyphs(FONT_PATH, TEXT)
    print(f"Extracted {len(glyphs)} glyphs. UPEM={upem}, ascent={ascent}, descent={descent}")
    for g in glyphs:
        path_preview = g["path"][:80] if g["path"] else "(empty)"
        print(f"  '{g['char']}' -> {g['name']}, advance={g['advance']}, path_len={len(g['path'])}, preview={path_preview}...")

    svg_dark, w, h = build_svg(glyphs, upem, ascent, descent, COLORS_DARK)
    with open(OUT_SVG_DARK, "w", encoding="utf-8") as f:
        f.write(svg_dark)
    print(f"Wrote DARK SVG:  {OUT_SVG_DARK}  ({w} x {h})")

    svg_light, _, _ = build_svg(glyphs, upem, ascent, descent, COLORS_LIGHT)
    with open(OUT_SVG_LIGHT, "w", encoding="utf-8") as f:
        f.write(svg_light)
    print(f"Wrote LIGHT SVG: {OUT_SVG_LIGHT}")

    vd_dark = build_vector_drawable(glyphs, upem, ascent, descent, COLORS_DARK)
    with open(OUT_VECTOR_XML_DARK, "w", encoding="utf-8") as f:
        f.write(vd_dark)
    print(f"Wrote DARK VectorDrawable:  {OUT_VECTOR_XML_DARK}")

    vd_light = build_vector_drawable(glyphs, upem, ascent, descent, COLORS_LIGHT)
    with open(OUT_VECTOR_XML_LIGHT, "w", encoding="utf-8") as f:
        f.write(vd_light)
    print(f"Wrote LIGHT VectorDrawable: {OUT_VECTOR_XML_LIGHT}")


if __name__ == "__main__":
    main()

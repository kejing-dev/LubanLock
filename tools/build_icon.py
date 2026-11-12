"""Luban Lock icon (design-package appIcon): a turquoise #3FB8AF disc (registered in icon-colors.md, no other app uses it),
a pale-beech #E8C9A0 six-piece burr seen isometrically with fine dark-walnut #4A3222 edges, and the key stick (lower
left, pointing at you) pulled half out to show it is whole. No text.

  python -X utf8 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon: disc / lock / key),
mipmap-xxxhdpi/ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same faces).
The sticks are drawn as unit cubes back to front (isometric voxel painter), so the interlocking reads right.
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
TURQ = (0x3F, 0xB8, 0xAF)
WALNUT = (0x4A, 0x32, 0x22)
TOP, LEFT, RIGHT = (0xF4, 0xE0, 0xBE), (0xE8, 0xC9, 0xA0), (0xC4, 0x9C, 0x6A)
KTOP, KLEFT, KRIGHT = (0xFA, 0xEB, 0xCF), (0xEF, 0xD3, 0xAA), (0xCF, 0xA6, 0x72)

# the six sticks as cell boxes (x0, x1, y0, y1, z0, z1); the key (5, left, along z) pulled 5 cells toward the viewer
STICKS = {
    1: (-4, 4, 0, 2, -1, 1), 2: (-4, 4, -2, 0, -1, 1),
    3: (-1, 1, -4, 4, 0, 2), 4: (-1, 1, -4, 4, -2, 0),
    5: (-2, 0, -1, 1, -4 + 5, 4 + 5), 6: (0, 2, -1, 1, -4, 4),
}
C30, S30 = math.cos(math.pi / 6), math.sin(math.pi / 6)


def raw(x, y, z):
    return ((x - z) * C30, -y + (x + z) * S30)


def _fit():
    pts = []
    for (x0, x1, y0, y1, z0, z1) in STICKS.values():
        for x in (x0, x1):
            for y in (y0, y1):
                for z in (z0, z1):
                    pts.append(raw(x, y, z))
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    k = 70.0 / max(max(xs) - min(xs), max(ys) - min(ys))  # adaptive-icon safe zone ~72 of 108
    return k, 54 - (max(xs) + min(xs)) / 2 * k, 54 - (max(ys) + min(ys)) / 2 * k


CELL, OX, OY = _fit()


def proj(x, y, z):
    px, py = raw(x, y, z)
    return (OX + px * CELL, OY + py * CELL)


def faces(only=None):
    """[(colour, [4 points], stick)] back to front."""
    cubes = []
    occupied = {}
    for sid, (x0, x1, y0, y1, z0, z1) in STICKS.items():
        for x in range(x0, x1):
            for y in range(y0, y1):
                for z in range(z0, z1):
                    occupied[(x, y, z)] = sid
    for (x, y, z), sid in occupied.items():
        cubes.append((x + y + z, x, y, z, sid))
    cubes.sort()
    out = []
    for _, x, y, z, sid in cubes:
        if only is not None and sid not in only:
            continue
        key = sid == 5
        top, left, right = (KTOP, KLEFT, KRIGHT) if key else (TOP, LEFT, RIGHT)
        # each face: colour, quad, stick, and which of its 4 edges are outlines (the neighbour face of the same stick and
        # orientation is missing): edges listed in quad order
        def edges(axis, cells):
            res = []
            for nb in cells:
                same = nb in occupied and occupied[nb] == sid and _exposed(nb, axis, occupied)
                res.append(not same)
            return res
        if (x, y + 1, z) not in occupied:
            out.append((top, [proj(x, y + 1, z), proj(x + 1, y + 1, z), proj(x + 1, y + 1, z + 1), proj(x, y + 1, z + 1)], sid,
                        edges(1, [(x, y, z - 1), (x + 1, y, z), (x, y, z + 1), (x - 1, y, z)])))
        if (x, y, z + 1) not in occupied:
            out.append((left, [proj(x, y, z + 1), proj(x + 1, y, z + 1), proj(x + 1, y + 1, z + 1), proj(x, y + 1, z + 1)], sid,
                        edges(2, [(x, y - 1, z), (x + 1, y, z), (x, y + 1, z), (x - 1, y, z)])))
        if (x + 1, y, z) not in occupied:
            out.append((right, [proj(x + 1, y, z), proj(x + 1, y, z + 1), proj(x + 1, y + 1, z + 1), proj(x + 1, y + 1, z)], sid,
                        edges(0, [(x, y, z - 1)if False else (x, y - 1, z), (x, y, z + 1), (x, y + 1, z), (x, y, z - 1)])))
    return out


def _exposed(c, axis, occupied):
    x, y, z = c
    n = [(x + 1, y, z), (x, y + 1, z), (x, y, z + 1)][axis]
    return n not in occupied


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=TURQ + (255,)))


def layer(sticks):
    def d(dr, k):
        fs = faces(sticks)
        for col, pts, _, _ in fs:
            dr.polygon(sc(pts, k), fill=col + (255,))
        for _, pts, _, edg in fs:
            q = sc(pts, k)
            for i, e in enumerate(edg):
                if e:
                    dr.line([q[i], q[(i + 1) % 4]], fill=WALNUT + (255,), width=max(1, int(0.5 * k)))
    return big(d)


def sdf(img):
    inside = img.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Luban Lock launcher foreground (tools/build_icon.py draws the same faces for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
    ]
    fs = faces()
    for col, pts, _, _ in fs:
        d = "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"
        lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(col), d))
    seg = []
    for _, pts, _, edg in fs:
        for i, e in enumerate(edg):
            if e:
                a, b = pts[i], pts[(i + 1) % 4]
                seg.append("M%.2f,%.2fL%.2f,%.2f" % (a[0], a[1], b[0], b[1]))
    lines.append('    <path android:strokeColor="%s" android:strokeWidth="0.5" android:pathData="%s" />' % (hexc(WALNUT), "".join(seg)))
    lines.append('</vector>')
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w", encoding="utf-8").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer({1, 2, 3, 4, 6}), layer({5})]
    for i, l in enumerate(layers):
        l.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(l).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    flat.alpha_composite(layers[0])
    flat.alpha_composite(layer(None))
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    col = os.path.join(RES, "values", "colors.xml")
    open(col, "w", encoding="utf-8").write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#3FB8AF</color>\n</resources>\n')
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()

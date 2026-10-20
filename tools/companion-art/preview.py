"""Renders every frame of both companions to full_sheet.png; the optional argument is the scale. Needs Pillow."""
import sys
from PIL import Image
from chars import draw_odin, draw_valk
from poses import ANIMS

BG = (28, 26, 34)


def to_img(grid, scale):
    img = Image.new('RGBA', (80 * scale, 80 * scale), BG + (255,))
    px = img.load()
    for y in range(80):
        for x in range(80):
            c = grid[y][x]
            if c is None: continue
            for dy in range(scale):
                for dx in range(scale):
                    px[x * scale + dx, y * scale + dy] = tuple(c) + (255,)
    return img


def all_frames():
    out = {}
    for ch, fn in (('ODIN', draw_odin), ('VALKYRIE', draw_valk)):
        for name, gen in ANIMS.items():
            out[(ch, name)] = [fn(p) for p in gen()]
    return out


if __name__ == '__main__':
    scale = int(sys.argv[1]) if len(sys.argv) > 1 else 4
    frames = all_frames()
    rows = list(frames.items())
    cols = max(len(v) for _, v in rows)
    S = 80 * scale; pad = 6
    sheet = Image.new('RGBA', (cols * (S + pad) + pad, len(rows) * (S + pad) + pad), (16, 15, 20, 255))
    for r, (_, fs) in enumerate(rows):
        for i, g in enumerate(fs):
            sheet.paste(to_img(g, scale), (pad + i * (S + pad), pad + r * (S + pad)))
    sheet.save('full_sheet.png')
    print(sheet.size)

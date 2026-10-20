"""Renders a scene of both companions (walk, thrust, cheer, celebrate) to begleiter.gif. Needs Pillow."""
from PIL import Image
from preview import all_frames
F = all_frames()
SW, SH, S = 200, 80, 3
BG = (24, 23, 30)

def sprite(grid, flip):
    im = Image.new('RGBA', (80, 80), (0, 0, 0, 0))
    px = im.load()
    for y in range(80):
        for x in range(80):
            c = grid[y][x]
            if c: px[x, y] = tuple(c) + (255,)
    return im.transpose(Image.FLIP_LEFT_RIGHT) if flip else im

def script():
    # (anim, frame index, x, flip, duration ms)
    seq = []; x = 10
    for k in range(8): seq.append(('IDLE', k % 4, x, False, 220))
    for k in range(30): x += 2.2; seq.append(('WALK', k % 6, x, False, 110))
    for k in range(4): seq.append(('IDLE', k % 4, x, False, 220))
    for k, d in enumerate([260, 90, 300, 120, 200]): seq.append(('ATTACK', k, x, False, d))
    for k in range(4): seq.append(('IDLE', k % 4, x, False, 220))
    for k in range(30): x -= 2.2; seq.append(('WALK', k % 6, x, True, 110))
    for k in range(6): seq.append(('CHEER', [0, 1, 2, 1, 0, 1][k], x, False, 160))
    for k, d in enumerate([200, 110, 220, 120, 160, 500]): seq.append(('CELEBRATE', k, x, False, d))
    for k in range(4): seq.append(('IDLE', k % 4, x, False, 220))
    return seq

frames, durs = [], []
for anim, i, x, flip, d in script():
    canvas = Image.new('RGBA', (SW, SH * 2 + 4), BG + (255,))
    for row, ch in enumerate(('ODIN', 'VALKYRIE')):
        sp = sprite(F[(ch, anim)][i], flip)
        ox = round(x) if not flip else round(x) - 23
        # shadow
        sh = Image.new('RGBA', (22, 3), (0, 0, 0, 90))
        canvas.alpha_composite(sh, (ox + 22, row * (SH + 4) + 77))
        canvas.alpha_composite(sp, (ox, row * (SH + 4)))
    frames.append(canvas.convert('RGB').resize((SW * S, (SH * 2 + 4) * S), Image.NEAREST)); durs.append(d)
frames[0].save('begleiter.gif', save_all=True, append_images=frames[1:], duration=durs, loop=0)
print(len(frames))

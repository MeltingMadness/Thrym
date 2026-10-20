"""Tiny pixel-art painter: materials with hue-shifted ramps, directional shading, outlines."""
import math
from PIL import Image

def hexc(h):
    h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

class Canvas:
    def __init__(self, w, h, ramps):
        self.w, self.h = w, h
        self.ramps = {k: [hexc(c) for c in v] for k, v in ramps.items()}
        self.mat = [[None]*w for _ in range(h)]
        self.lvl = [[0]*w for _ in range(h)]
        self.layer = [[-1]*w for _ in range(h)]
        self.n = 0
        self.override = {}

    def inb(self, x, y): return 0 <= x < self.w and 0 <= y < self.h

    # ---- masks
    @staticmethod
    def poly(points):
        def inside(x, y):
            px, py = x + 0.5, y + 0.5
            c = False; n = len(points)
            for i in range(n):
                x1, y1 = points[i]; x2, y2 = points[(i+1) % n]
                if (y1 > py) != (y2 > py):
                    xi = x1 + (py - y1) * (x2 - x1) / (y2 - y1)
                    if px < xi: c = not c
            return c
        return inside

    @staticmethod
    def ellipse(cx, cy, rx, ry):
        return lambda x, y: ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1.0

    @staticmethod
    def rect(x0, y0, x1, y1):
        return lambda x, y: x0 <= x <= x1 and y0 <= y <= y1

    @staticmethod
    def union(*ms): return lambda x, y: any(m(x, y) for m in ms)
    @staticmethod
    def minus(a, b): return lambda x, y: a(x, y) and not b(x, y)
    @staticmethod
    def inter(a, b): return lambda x, y: a(x, y) and b(x, y)

    # ---- painting
    sep_layers = None
    def shape(self, mask, mat, base=2, light=(-0.6, -0.8), grad=1.6, rim=True, clip=None, edge_dark=True, sep=False):
        """Fill mask with material; level = base + directional gradient; dark edges away from light."""
        pts = [(x, y) for y in range(self.h) for x in range(self.w) if mask(x, y) and (clip is None or clip(x, y))]
        if not pts: return
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        cx = (min(xs) + max(xs)) / 2; cy = (min(ys) + max(ys)) / 2
        sx = max(1, (max(xs) - min(xs)) / 2); sy = max(1, (max(ys) - min(ys)) / 2)
        lx, ly = light
        s = set(pts)
        top = len(self.ramps[mat]) - 1
        self.n += 1
        if sep:
            if self.sep_layers is None: self.sep_layers = set()
            self.sep_layers.add(self.n)
        for x, y in pts:
            d = -(((x - cx) / sx) * lx + ((y - cy) / sy) * ly) / math.hypot(lx, ly)  # +1 toward light
            v = base + d * grad
            if edge_dark:
                # edge toward the dark side gets darker, edge toward light brighter
                dark_nb = (x - round(lx), y - round(ly))
                lit_nb = (x + round(lx), y + round(ly))
                if dark_nb not in s and (x+1, y+1) not in s: v -= 1.2
                elif rim and lit_nb not in s and (x-1, y-1) not in s: v += 0.8
            lv = max(0, min(top, int(round(v))))
            self.mat[y][x] = mat; self.lvl[y][x] = lv; self.layer[y][x] = self.n

    def flat(self, mask, mat, lv, clip=None):
        self.n += 1
        for y in range(self.h):
            for x in range(self.w):
                if mask(x, y) and (clip is None or clip(x, y)):
                    self.mat[y][x] = mat; self.lvl[y][x] = lv; self.layer[y][x] = self.n

    def px(self, x, y, mat, lv):
        if self.inb(x, y):
            self.mat[y][x] = mat; self.lvl[y][x] = lv; self.layer[y][x] = self.n + 1

    def pxs(self, pts, mat, lv):
        for x, y in pts: self.px(x, y, mat, lv)

    def line(self, x0, y0, x1, y1, mat, lv):
        n = max(abs(x1 - x0), abs(y1 - y0)) or 1
        for i in range(n + 1):
            self.px(round(x0 + (x1 - x0) * i / n), round(y0 + (y1 - y0) * i / n), mat, lv)

    def shade(self, mask, delta, clip=None):
        for y in range(self.h):
            for x in range(self.w):
                if self.mat[y][x] and mask(x, y) and (clip is None or clip(x, y)):
                    top = len(self.ramps[self.mat[y][x]]) - 1
                    self.lvl[y][x] = max(0, min(top, self.lvl[y][x] + delta))

    def color(self, x, y):
        m = self.mat[y][x]
        if m is None: return None
        return self.ramps[m][self.lvl[y][x]]

    # ---- finishing
    def inner_lines(self, mats_front=None):
        """Darken pixels where a later layer sits above/left of an earlier one (separation lines)."""
        out = [row[:] for row in self.lvl]
        for y in range(self.h):
            for x in range(self.w):
                m = self.mat[y][x]
                if m is None: continue
                for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)):
                    nx, ny = x + dx, y + dy
                    if not self.inb(nx, ny): continue
                    nm = self.mat[ny][nx]
                    if nm is not None and nm != m and self.layer[ny][nx] > self.layer[y][x]:
                        out[y][x] = 0
                        break
                    if nm == m and self.sep_layers and self.layer[ny][nx] in self.sep_layers and self.layer[ny][nx] > self.layer[y][x]:
                        out[y][x] = max(0, self.lvl[y][x] - 2)
                        break
        self.lvl = out

    def outline(self, color='#0d0b12', selective=True):
        oc = hexc(color)
        img = self.render_raw()
        res = [row[:] for row in img]
        for y in range(self.h):
            for x in range(self.w):
                if img[y][x] is not None: continue
                nbs = [(x+dx, y+dy) for dx, dy in ((0,1),(1,0),(0,-1),(-1,0)) if self.inb(x+dx, y+dy) and img[y+dy][x+dx] is not None]
                if nbs:
                    res[y][x] = oc
        return res

    def render_raw(self):
        return [[self.color(x, y) for x in range(self.w)] for y in range(self.h)]

def save(grid, path, scale=8, bg=(28, 26, 34)):
    h = len(grid); w = len(grid[0])
    img = Image.new('RGB', (w * scale, h * scale), bg)
    for y in range(h):
        for x in range(w):
            c = grid[y][x]
            if c is None: continue
            for dy in range(scale):
                for dx in range(scale):
                    img.putpixel((x*scale+dx, y*scale+dy), c)
    img.save(path)

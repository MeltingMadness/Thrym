"""Odin and the valkyrie, drawn on the rig."""
from px import Canvas
from rig import solve, capsule, foot, spear, up, down, add, E, P, U, M, Re, GY, K, W, H

ODIN = {
    'cloak': ['#0f0e17', '#1a1b2a', '#262b3e', '#364057', '#56637f'],
    'tunic': ['#15131a', '#262229', '#3a333a', '#53494d', '#706369'],
    'skin': ['#4a2b25', '#7d4a38', '#ad6f52', '#d49773', '#efc09a'],
    'beard': ['#2e2c35', '#56535d', '#86838a', '#b8b4af', '#e6e2d8'],
    'fur': ['#231c18', '#41352d', '#625245', '#877461', '#ad9a82'],
    'steel': ['#1f222a', '#3a404c', '#5f6774', '#8e96a3', '#d5dbe2'],
    'gold': ['#3f250c', '#734a14', '#a9781d', '#d9a935', '#f5d877'],
    'leather': ['#0e0908', '#1d130f', '#311f17', '#4a3024', '#654332'],
    'glow': ['#173f57', '#2a7fa3', '#5fc7ea', '#c4f4ff', '#ffffff'],
    'wood': ['#24170e', '#3f2816', '#613e22', '#875a33'],
    'fx': ['#2a7fa3', '#5fc7ea', '#c4f4ff', '#ffffff'],
    'dust': ['#3a3646', '#5a5566', '#7d7889'],
}

VALK = {
    'cape': ['#2a0d12', '#4a141c', '#6e1f26', '#972d2e', '#c04a3c'],
    'skin': ['#6b3d33', '#a0644f', '#c98f73', '#e8b597', '#fad8bf'],
    'hair': ['#4a3318', '#7a5a2c', '#a8853f', '#d2b264', '#efdc9c'],
    'steel': ['#1c2029', '#363d4b', '#5b6474', '#8b95a5', '#cfd6df'],
    'gold': ['#3f250c', '#734a14', '#a9781d', '#d9a935', '#f5d877'],
    'feather': ['#3b4150', '#6c7486', '#a2a9b8', '#d3d8e1', '#f6f8fb'],
    'leather': ['#1a110d', '#2f1f17', '#4a3124', '#68473a', '#86604d'],
    'wood': ['#24170e', '#3f2816', '#613e22', '#875a33'],
    'eye': ['#123247', '#2b6d93', '#6fb3d8'],
    'lip': ['#6e2e2c', '#9a4743', '#bd6a60'],
    'fx': ['#a9781d', '#d9a935', '#f5d877', '#ffffff'],
    'dust': ['#3a3646', '#5a5566', '#7d7889'],
}


def rp(p): return (round(p[0]), round(p[1]))


def effects(c, pose, tip=None, mat='fx'):
    for fx in pose.get('fx', []):
        kind = fx[0]
        if kind == 'tipspark' and tip:
            x, y = round(tip[0]), round(tip[1]) - 1
            s = fx[1]
            c.px(x, y, mat, 3)
            for i in range(1, s + 1):
                for dx, dy in ((i, 0), (-i, 0), (0, i), (0, -i)):
                    c.px(x + dx, y + dy, mat, 2 if i == 1 else 1)
            continue
        if kind == 'spark':  # 4-point star, position in unscaled units
            _, x, y, s = fx
            x, y = round(x * K), round(y * K)
            c.px(x, y, mat, 3)
            for i in range(1, s + 1):
                lv = 2 if i == 1 else 1
                for dx, dy in ((i, 0), (-i, 0), (0, i), (0, -i)):
                    c.px(x + dx, y + dy, mat, lv)
        elif kind == 'dot':
            _, x, y, lv = fx
            c.px(round(x * K), round(y * K), mat, lv)
        elif kind == 'smear':
            _, pts = fx
            for i, (x, y) in enumerate(pts):
                c.px(round(x * K), round(y * K), mat, min(3, 1 + i * 3 // max(1, len(pts) - 1)))
        elif kind == 'dust':
            _, x, y, r = fx
            c.flat(E(x * K, GY + 0.5, r * K, r * K * 0.55), 'dust', 1)
            c.px(round(x * K - r * 0.4), round(GY - r * 0.3), 'dust', 2)


def limb(c, a, b, r0, r1, mat, base, grad=1.0, sep=False):
    c.shape(capsule(a, b, r0 * K, r1 * K), mat, base, grad=grad, sep=sep)


class Rel:
    """Offsets relative to a point, scaled by K."""
    def __init__(self, origin): self.x, self.y = origin
    def __call__(self, dx, dy): return (self.x + dx * K, self.y + dy * K)
    def pts(self, *pts): return [self(dx, dy) for dx, dy in pts]
    def ell(self, dx, dy, rx, ry): return E(self.x + dx * K, self.y + dy * K, rx * K, ry * K)


def cape_shape(j, pose, hem_drop):
    hip, neck = j['hip'], j['neck']
    flow = pose.get('flow', 0.3)
    lift = pose.get('lift', 0) * K
    hem_y = GY - hem_drop * K - lift * 0.5
    hb = (hip[0] - (7 + flow * 9) * K, hem_y - flow * 7 * K + pose.get('sway', 0) * K)
    back = add(neck, (-4.2 * K, 1 * K))
    shape = P([(neck[0] + 1.5 * K, neck[1] - 1 * K), back, (hip[0] - (5.5 + flow * 3) * K, hip[1]),
               hb, (hb[0] + 3.5 * K, hb[1] + 2 * K), (hip[0] - 1 * K, hem_y + 1), (hip[0] + 1 * K, hip[1] + 3 * K)])
    return shape, back, hb


def arm_spear(c, j, pose, mats):
    sp = pose.get('spear')
    if sp:
        hand = j[sp.get('hand', 'near') + '_hand']
        return spear(c, hand, sp['angle'], sp['up'], sp['down'], mats)


def draw_odin(pose):
    c = Canvas(W, H, ODIN)
    j = solve(pose)
    hip, neck, chest = j['hip'], j['neck'], j['chest']
    lean = pose.get('lean', 0)
    mats = {'wood': 'wood', 'steel': 'steel', 'gold': 'gold'}
    sp = pose.get('spear') or {}
    if sp.get('hand') == 'far':
        arm_spear(c, j, pose, mats)

    cloak, back, hb = cape_shape(j, pose, 2.5)
    c.shape(cloak, 'cloak', 2, grad=1.2)
    c.line(round(back[0] + 2), round(back[1] + 5), round(hb[0] + 4), round(hb[1]), 'cloak', 1)
    c.line(round(back[0] + 4), round(back[1] + 8), round(hb[0] + 8), round(hb[1] + 2), 'cloak', 1)

    # far side, in shadow
    limb(c, j['far_sh'], j['far_elbow'], 2.4, 2.1, 'cloak', 1)
    limb(c, j['far_elbow'], j['far_hand'], 2.0, 1.8, 'leather', 2)
    c.shape(E(*j['far_hand'], 1.8 * K, 1.8 * K), 'skin', 1)
    limb(c, hip, j['far_knee'], 2.8, 2.4, 'tunic', 1)
    limb(c, j['far_knee'], j['far_ankle'], 2.3, 2.0, 'leather', 1)
    foot(c, j['far_ankle'], 'leather', 1)

    # torso and tunic skirt
    torso = U(capsule(hip, chest, 4.2 * K, 5.4 * K), capsule(chest, neck, 5.4 * K, 3.8 * K))
    c.shape(torso, 'tunic', 2, grad=1.3)
    sd = down(lean * 0.5, 1)
    sk = add(hip, (sd[0] * 6 * K, sd[1] * 6 * K))
    c.shape(P([(hip[0] - 4.5 * K, hip[1] - 1), (hip[0] + 4.5 * K, hip[1] - 1), (sk[0] + 5 * K, sk[1]), (sk[0] - 5.6 * K, sk[1])]), 'tunic', 2, grad=1.0)
    c.line(round(hip[0]), round(hip[1] + 2), round(sk[0]), round(sk[1] - 1), 'tunic', 1)

    # near leg
    limb(c, hip, j['near_knee'], 2.9, 2.5, 'tunic', 2, sep=True)
    limb(c, j['near_knee'], j['near_ankle'], 2.4, 2.1, 'leather', 3)
    c.shape(E(j['near_knee'][0] + 0.6 * K, j['near_knee'][1] + 0.8 * K, 2.6 * K, 1.5 * K), 'leather', 4)
    foot(c, j['near_ankle'], 'leather', 3)

    # belt with buckle
    bu = up(lean, 1)
    bc = add(hip, (bu[0] * 1.6 * K, bu[1] * 1.6 * K))
    c.shape(capsule((bc[0] - 4.6 * K, bc[1] + 0.4), (bc[0] + 4.6 * K, bc[1] - 0.4), 1.2 * K, 1.2 * K), 'leather', 2, grad=0.5)
    c.shape(E(bc[0] + 2.2 * K, bc[1], 1.4 * K, 1.3 * K), 'gold', 3, grad=1.0)

    # fur mantle on the shoulders
    c.shape(E(neck[0] - 1.0 * K, neck[1] + 2.0 * K, 6.2 * K, 3.4 * K), 'fur', 2, grad=1.3)
    for dx in (-5, -3, -1, 1, 3):
        c.px(round(neck[0] + dx * K), round(neck[1] + 4.6 * K), 'fur', 1)
        c.px(round(neck[0] + dx * K + 1), round(neck[1] + 4.0 * K), 'fur', 3)

    # head
    r = Rel(j['head'])
    hood = U(r.ell(-1.4, -0.6, 5.8, 6.0), P(r.pts((-6.6, 0), (-7.2, 6.5), (-1, 6.5), (1, -4))))
    c.shape(hood, 'cloak', 3, grad=1.6)
    face = U(r.ell(1.1, 0.5, 3.9, 4.4), P(r.pts((1, -3.2), (5.4, -1.2), (4.8, 4.2), (0, 4.4))))
    c.shape(face, 'skin', 3, grad=1.2)
    c.shape(P(r.pts((-4, -6.0), (3.6, -5.2), (5.4, -2.6), (1.2, -3.0), (-3, -0.8))), 'cloak', 3, grad=1.0, sep=True)
    beard = P(r.pts((-0.6, 1.2), (2.6, 1.8), (5.6, 1.2), (6.0, 4.8), (5.0, 9.5), (3.4, 13.5), (1.8, 10.0), (-0.4, 7.4), (-1.4, 3.6)))
    c.shape(beard, 'beard', 3, grad=1.2)
    c.line(*rp(r(3.2, 4.5)), *rp(r(3.4, 10.5)), 'beard', 2)
    c.line(*rp(r(1.0, 4.5)), *rp(r(1.2, 8.0)), 'beard', 2)
    c.inner_lines()
    # face details after the separation lines
    c.px(*rp(r(5.8, -0.6)), 'skin', 4)
    c.px(*rp(r(5.8, 0.4)), 'skin', 2)
    c.line(*rp(r(3.4, 1.8)), *rp(r(5.8, 1.8)), 'beard', 4)
    c.line(*rp(r(-0.4, -2.6)), *rp(r(4.4, -2.6)), 'cloak', 0)   # brow shadow
    ex, ey = rp(r(3.2, -1.3))
    glow = pose.get('glow', 0)
    c.px(ex, ey, 'glow', 3 + glow)
    c.px(ex - 1, ey, 'glow', 1 + glow)
    c.px(ex + 1, ey, 'glow', 2 if glow else 1)
    if glow:
        c.px(ex, ey - 1, 'glow', 1)
    c.line(*rp(r(2.0, -3.4)), *rp(r(-4.4, 0.6)), 'leather', 0)    # eyepatch strap

    # near arm with Gungnir
    tip = None
    if sp.get('hand', 'near') == 'near' and sp:
        tip = arm_spear(c, j, pose, mats)
    fa = pose['arms']['near'][1]
    limb(c, j['near_sh'], j['near_elbow'], 2.6, 2.2, 'cloak', 3)
    limb(c, j['near_elbow'], j['near_hand'], 2.1, 1.9, 'leather', 3)
    c.shape(capsule(add(j['near_elbow'], down(fa, 2 * K)), add(j['near_elbow'], down(fa, 4.4 * K)), 2.2 * K, 2.2 * K), 'gold', 2, grad=0.8)
    c.shape(E(*j['near_hand'], 1.9 * K, 1.9 * K), 'skin', 3)
    effects(c, pose, tip)
    return c.outline()


def draw_valk(pose):
    c = Canvas(W, H, VALK)
    j = solve(pose)
    hip, neck, chest = j['hip'], j['neck'], j['chest']
    lean = pose.get('lean', 0)
    flow = pose.get('flow', 0.3)
    mats = {'wood': 'wood', 'steel': 'steel', 'gold': 'gold'}
    sp = pose.get('spear') or {}
    if sp.get('hand') == 'far':
        arm_spear(c, j, pose, mats)
    r = Rel(j['head'])

    # braid flowing behind
    for i in range(7):
        t = i / 6
        p = r(-4.4 - t * (3 + flow * 8), 1.5 + i * 2.6 - t * flow * 4)
        c.shape(E(p[0], p[1], 2.1 * K, 1.7 * K), 'hair', 3 if i % 2 == 0 else 2, grad=1.0, sep=True)
    tip = r(-4.6 - (3 + flow * 8), 19.5 - flow * 4)
    c.shape(E(tip[0], tip[1], 1.2 * K, 1.0 * K), 'gold', 3)

    cape, back, hb = cape_shape(j, pose, 7)
    c.shape(cape, 'cape', 2, grad=1.2)
    c.line(round(back[0] + 2), round(back[1] + 5), round(hb[0] + 4), round(hb[1]), 'cape', 1)
    c.line(round(back[0] + 4), round(back[1] + 8), round(hb[0] + 8), round(hb[1] + 2), 'cape', 3)

    # far side
    limb(c, j['far_sh'], j['far_elbow'], 2.1, 1.9, 'steel', 1)
    limb(c, j['far_elbow'], j['far_hand'], 1.8, 1.6, 'leather', 2)
    c.shape(E(*j['far_hand'], 1.7 * K, 1.7 * K), 'skin', 1)
    limb(c, hip, j['far_knee'], 2.4, 2.2, 'leather', 1)
    limb(c, j['far_knee'], j['far_ankle'], 2.1, 1.8, 'steel', 1)
    foot(c, j['far_ankle'], 'leather', 1)

    # breastplate and strip skirt
    torso = U(capsule(hip, chest, 3.5 * K, 4.6 * K), capsule(chest, neck, 4.6 * K, 3.3 * K))
    c.shape(torso, 'steel', 2, grad=1.6)
    cu = up(lean, 1)
    c.line(*rp(add(hip, (cu[0] * 4 * K + 2.5 * K, cu[1] * 4 * K))), *rp(add(hip, (cu[0] * 11 * K + 3.6 * K, cu[1] * 11 * K))), 'steel', 4)
    sd = down(lean * 0.5, 1)
    sk = add(hip, (sd[0] * 6 * K, sd[1] * 6 * K))
    c.shape(P([(hip[0] - 4.4 * K, hip[1] - 1), (hip[0] + 4.4 * K, hip[1] - 1), (sk[0] + 5 * K, sk[1]), (sk[0] - 5.4 * K, sk[1])]), 'leather', 3, grad=1.0)
    for dx in (-3.6, -1.2, 1.2, 3.6):
        c.line(round(hip[0] + dx * K), round(hip[1] + 2), round(sk[0] + dx * 1.15 * K), round(sk[1] - 1), 'leather', 1)

    # near leg
    limb(c, hip, j['near_knee'], 2.5, 2.3, 'leather', 2, sep=True)
    limb(c, j['near_knee'], j['near_ankle'], 2.2, 1.9, 'steel', 3)
    c.shape(E(j['near_knee'][0] + 0.7 * K, j['near_knee'][1] + 0.4 * K, 2.2 * K, 1.7 * K), 'steel', 4)
    foot(c, j['near_ankle'], 'leather', 3)

    # belt, gorget, pauldron
    bc = add(hip, (cu[0] * 1.6 * K, cu[1] * 1.6 * K))
    c.shape(capsule((bc[0] - 4.2 * K, bc[1] + 0.4), (bc[0] + 4.2 * K, bc[1] - 0.4), 1.1 * K, 1.1 * K), 'gold', 2, grad=0.6)
    c.shape(E(neck[0] - 0.4 * K, neck[1] + 1.3 * K, 4.4 * K, 2.0 * K), 'gold', 3, grad=1.2)
    c.shape(E(j['near_sh'][0] - 0.4 * K, j['near_sh'][1] + 0.6 * K, 3.5 * K, 2.8 * K), 'steel', 3, grad=1.5, sep=True)
    c.line(*rp(add(j['near_sh'], (-3.4 * K, 2.6 * K))), *rp(add(j['near_sh'], (2.6 * K, 2.6 * K))), 'gold', 3)

    # head
    face = U(r.ell(1.0, 0.6, 3.7, 4.2), P(r.pts((1, -3), (5.0, -1.0), (4.6, 3.6), (0.6, 4.6))))
    c.shape(face, 'skin', 3, grad=1.2)
    c.shape(P(r.pts((-5, -2), (0.4, -2), (1.0, 1.2), (-0.4, 4.8), (-3.8, 5.0), (-5.4, 1.6))), 'hair', 3, grad=1.2)
    helm = M(U(r.ell(-0.2, -1.8, 5.6, 4.8), P(r.pts((-5.6, -1.8), (5.3, -1.8), (5.3, -0.4), (-5.6, -0.4)))),
             Re(-99, round(r(0, 0)[1]), 999, 999))
    c.shape(helm, 'steel', 3, grad=1.6)
    c.shape(capsule(r(-5.4, -1.0), r(5.2, -1.0), 0.9 * K, 0.9 * K), 'gold', 3, grad=0.8)
    c.shape(capsule(r(4.2, -1.2), r(4.9, 2.0), 0.7 * K, 0.6 * K), 'steel', 3, grad=0.6)
    wx, wy = -2.6, -3.8
    wing = P(r.pts((wx + 1, wy + 1), (wx - 1, wy - 3.2), (wx - 4.2, wy - 7), (wx - 8, wy - 8.6), (wx - 6.8, wy - 6),
                   (wx - 10, wy - 5.4), (wx - 7, wy - 3.4), (wx - 9.8, wy - 2.3), (wx - 6.2, wy - 0.6), (wx - 7.8, wy + 0.9), (wx - 3.2, wy + 1.3)))
    c.shape(wing, 'feather', 3, grad=1.3)
    c.line(*rp(r(wx, wy - 1)), *rp(r(wx - 6.4, wy - 5.4)), 'feather', 1)
    c.line(*rp(r(wx, wy)), *rp(r(wx - 6.4, wy - 1.6)), 'feather', 1)
    c.inner_lines()
    ex, ey = rp(r(2.8, 0.4))
    c.px(ex, ey, 'eye', 2)
    c.px(ex - 1, ey, 'eye', 0)
    c.line(ex - 1, ey - 1, ex + 1, ey - 1, 'leather', 0)
    c.px(*rp(r(5.2, 1.4)), 'skin', 4)
    m = rp(r(3.6, 3.0))
    if pose.get('smile'):
        c.px(m[0], m[1], 'lip', 0); c.px(m[0] + 1, m[1], 'lip', 0); c.px(m[0] - 1, m[1] - 1, 'lip', 1)
    else:
        c.px(m[0], m[1], 'lip', 1); c.px(m[0] + 1, m[1], 'lip', 1)

    tip = None
    if sp.get('hand', 'near') == 'near' and sp:
        tip = arm_spear(c, j, pose, mats)
    fa = pose['arms']['near'][1]
    limb(c, j['near_sh'], j['near_elbow'], 2.2, 1.9, 'steel', 2)
    limb(c, j['near_elbow'], j['near_hand'], 1.9, 1.7, 'leather', 3)
    c.shape(capsule(add(j['near_elbow'], down(fa, 1.6 * K)), add(j['near_elbow'], down(fa, 3.8 * K)), 2.0 * K, 2.0 * K), 'gold', 2, grad=0.8)
    c.shape(E(*j['near_hand'], 1.8 * K, 1.8 * K), 'skin', 3)
    effects(c, pose, tip)
    return c.outline()

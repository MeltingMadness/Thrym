"""Side-view rig for full-body companion frames (facing right). Poses -> 80x80 pixel frames."""
import math
from px import Canvas

K = 1.3
W = H = 80
GY = 77  # lowest row of the feet (outline goes below)
E, P, U, M, Re = Canvas.ellipse, Canvas.poly, Canvas.union, Canvas.minus, Canvas.rect


def rad(d): return math.radians(d)
def down(a, l): return (math.sin(rad(a)) * l, math.cos(rad(a)) * l)       # 0 = straight down
def up(a, l): return (math.sin(rad(a)) * l, -math.cos(rad(a)) * l)        # 0 = straight up
def add(p, v): return (p[0] + v[0], p[1] + v[1])


def capsule(p0, p1, r0, r1):
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    n = math.hypot(dx, dy) or 1
    nx, ny = -dy / n, dx / n
    quad = P([(p0[0] + nx * r0, p0[1] + ny * r0), (p1[0] + nx * r1, p1[1] + ny * r1),
              (p1[0] - nx * r1, p1[1] - ny * r1), (p0[0] - nx * r0, p0[1] - ny * r0)])
    return U(quad, E(p0[0], p0[1], r0, r0), E(p1[0], p1[1], r1, r1))


class Body:
    """Dimensions shared by both characters (pixels)."""
    thigh, shin = 9.8 * K, 9.4 * K
    torso = 12.8 * K
    upper, fore = 7.2 * K, 6.8 * K


def solve(pose, B=Body):
    """Forward kinematics. Returns joint dict with hip placed so the lowest foot touches the ground."""
    j = {}
    hip = (0.0, 0.0)
    feet = {}
    for side in ('far', 'near'):
        ta, sa = pose['legs'][side]
        knee = add(hip, down(ta, B.thigh))
        ankle = add(knee, down(sa, B.shin))
        j[side + '_knee'], j[side + '_ankle'] = knee, ankle
        feet[side] = ankle[1] + 1.5 * K
    lowest = max(feet.values())
    oy = GY - lowest - pose.get('lift', 0) * K
    ox = pose.get('x', 25) * K
    for k in list(j): j[k] = (j[k][0] + ox, j[k][1] + oy)
    hip = (ox, oy)
    j['hip'] = hip
    lean = pose.get('lean', 0)
    neck = add(hip, up(lean, B.torso))
    j['neck'] = neck
    j['chest'] = add(hip, up(lean, B.torso * 0.62))
    j['head'] = add(neck, up(lean + pose.get('head', 0), 5.4 * K))
    for side in ('far', 'near'):
        sh = add(neck, down(lean, 2.0 * K))
        sh = (sh[0] + (-1.2 if side == 'far' else 0.6) * K, sh[1])
        ua, fa = pose['arms'][side]
        elbow = add(sh, down(ua, B.upper))
        hand = add(elbow, down(fa, B.fore))
        j[side + '_sh'], j[side + '_elbow'], j[side + '_hand'] = sh, elbow, hand
    return j


def foot(c, ankle, mat, base, toe=3.8):
    x, y = ankle
    k = K
    m = P([(x - 2.0 * k, y - 1.6 * k), (x + 1.2 * k, y - 1.7 * k), (x + (toe + 0.4) * k, y + 0.1 * k),
           (x + (toe + 0.5) * k, y + 1.5 * k), (x - 2.1 * k, y + 1.5 * k)])
    c.shape(m, mat, base, grad=1.0)
    c.line(round(x - 2 * k), round(y + 1.5 * k), round(x + toe * k), round(y + 1.5 * k), mat, 0)


def spear(c, hand, angle, up_len, down_len, mats):
    """Spear through [hand]; angle 0 = tip straight up, 90 = forward. Lengths in unscaled pixels."""
    d = up(angle, 1)
    up_len, down_len = up_len * K, (GY - hand[1]) / max(0.2, math.cos(math.radians(angle))) if down_len == 'ground' else down_len * K
    tip = add(hand, (d[0] * up_len, d[1] * up_len))
    butt = add(hand, (-d[0] * down_len, -d[1] * down_len))
    c.shape(capsule(butt, tip, 0.9, 0.9), mats['wood'], 2, grad=0.5, edge_dark=False)
    nx, ny = -d[1], d[0]
    L, Wd = 8.5, 2.3
    side_a = [(0, 0.9), (2.4, Wd), (5.4, Wd * 0.75), (L, 0.0)]
    pts = [(tip[0] + d[0] * t + nx * w, tip[1] + d[1] * t + ny * w) for t, w in side_a]
    pts += [(tip[0] + d[0] * t - nx * w, tip[1] + d[1] * t - ny * w) for t, w in reversed(side_a[:-1])]
    c.shape(P(pts), mats['steel'], 3, grad=1.3)
    a = add(tip, (d[0] * 1.6, d[1] * 1.6)); b = add(tip, (d[0] * 6.4, d[1] * 6.4))
    c.line(round(a[0]), round(a[1]), round(b[0]), round(b[1]), mats['steel'], 4)
    c.shape(capsule(add(tip, (-d[0] * 2.4, -d[1] * 2.4)), add(tip, (-d[0] * 0.2, -d[1] * 0.2)), 1.5, 1.5), mats['gold'], 3, grad=1.2)
    return add(tip, (d[0] * L, d[1] * L))

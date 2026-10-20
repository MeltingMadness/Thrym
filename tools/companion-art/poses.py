"""Animation poses. Angles: legs/arms from straight down, + = forward (to the right).
Positions of effects are in unscaled units (x, y * K = pixels)."""
import math


def base(**kw):
    p = {
        'x': 22, 'lean': 0, 'head': 0, 'flow': 0.15, 'sway': 0, 'lift': 0,
        'legs': {'far': (-7, -3), 'near': (7, 3)},
        'arms': {'far': (-12, 0), 'near': (18, 75)},
        'spear': {'hand': 'near', 'angle': 0, 'up': 20, 'down': 'ground'},
    }
    for k, v in kw.items():
        if isinstance(v, dict) and isinstance(p.get(k), dict):
            p[k] = {**p[k], **v}
        else:
            p[k] = v
    return p


def idle():
    out = []
    for i in range(4):
        b = [0, 0.5, 1, 0.5][i]
        out.append(base(lift=-b * 0.5, sway=[0, 0.5, 1, -0.5][i], flow=[0.12, 0.16, 0.2, 0.26][i],
                        arms={'far': (-12 + b * 2, 0 + b * 3), 'near': (18, 75)}, head=b * 1.5))
    return out


def walk(n=6):
    out = []
    for i in range(n):
        t = 2 * math.pi * i / n
        s = math.sin(t)
        thigh = 28 * s
        near_knee = thigh - 35 * max(0.0, -math.cos(t))
        far_knee = -thigh - 35 * max(0.0, math.cos(t))
        out.append(base(
            lean=7, flow=0.5 + 0.12 * abs(s), sway=round(math.cos(t)),
            legs={'near': (thigh, near_knee), 'far': (-thigh, far_knee)},
            arms={'near': (10 - 8 * s, 70), 'far': (-18 * s, 15 - 12 * s)},
            spear={'hand': 'near', 'angle': 35 + 3 * s, 'up': 22, 'down': 16},
            lift=0.8 * abs(math.cos(t)),
        ))
    return out


def attack():
    return [
        base(lean=-5, flow=0.25, legs={'near': (30, 10), 'far': (-28, -14)}, arms={'near': (-40, 55), 'far': (35, 80)},
             spear={'hand': 'near', 'angle': 84, 'up': 18, 'down': 20}),
        base(lean=15, flow=0.65, legs={'near': (40, 12), 'far': (-38, -20)}, arms={'near': (72, 86), 'far': (40, 88)},
             spear={'hand': 'near', 'angle': 88, 'up': 18, 'down': 20},
             fx=[('smear', [(36, 33), (40, 33), (44, 32.5), (48, 32.5)])]),
        base(lean=17, flow=0.75, legs={'near': (42, 14), 'far': (-40, -20)}, arms={'near': (84, 90), 'far': (45, 92)},
             spear={'hand': 'near', 'angle': 89, 'up': 18, 'down': 20}, fx=[('tipspark', 2)]),
        base(lean=10, flow=0.5, legs={'near': (34, 10), 'far': (-32, -16)}, arms={'near': (58, 80), 'far': (35, 75)},
             spear={'hand': 'near', 'angle': 86, 'up': 18, 'down': 20}),
        base(lean=4, flow=0.3, legs={'near': (16, 6), 'far': (-16, -6)}, arms={'near': (30, 70), 'far': (5, 25)},
             spear={'hand': 'near', 'angle': 30, 'up': 22, 'down': 16}),
    ]


def cheer():
    out = []
    for i in range(3):
        b = [0, 1, 0.5][i]
        out.append(base(lean=-2, head=-6, lift=b * 0.6, flow=0.2 + 0.1 * b, smile=True, glow=1,
                        legs={'near': (14, 4), 'far': (-12, -4)},
                        arms={'near': (115 + 8 * b, 150 + 6 * b), 'far': (172 - 6 * b, 182 - 4 * b)},
                        spear={'hand': 'near', 'angle': 22 - 4 * b, 'up': 10, 'down': 18},
                        fx=[('tipspark', 1 + (i == 1))]))
    return out


def celebrate():
    crouch = {'near': (48, -12), 'far': (32, -28)}
    tuck = {'near': (55, -10), 'far': (20, -40)}
    salute = {'near': (125, 158), 'far': (176, 184)}
    return [
        base(lean=12, flow=0.2, legs=crouch, arms={'near': (30, 75), 'far': (-30, 0)},
             spear={'hand': 'near', 'angle': 25, 'up': 18, 'down': 18}),
        base(lean=-2, head=-6, lift=3, flow=0.0, sway=3, smile=True, glow=1,
             legs={'near': (14, 2), 'far': (-8, -16)}, arms=salute,
             spear={'hand': 'near', 'angle': 18, 'up': 10, 'down': 18}, fx=[('tipspark', 1)]),
        base(lean=-4, head=-8, lift=5, flow=-0.1, sway=5, smile=True, glow=1, legs=tuck, arms=salute,
             spear={'hand': 'near', 'angle': 16, 'up': 10, 'down': 18},
             fx=[('tipspark', 3), ('spark', 8, 22, 1), ('spark', 50, 18, 1), ('dot', 16, 10, 2), ('dot', 44, 30, 2), ('dot', 54, 34, 1)]),
        base(lean=-2, head=-6, lift=3, flow=0.1, sway=3, smile=True, glow=1,
             legs={'near': (20, 0), 'far': (-6, -18)}, arms=salute,
             spear={'hand': 'near', 'angle': 18, 'up': 10, 'down': 18},
             fx=[('tipspark', 1), ('dot', 6, 30, 1), ('dot', 52, 26, 1), ('dot', 18, 16, 1)]),
        base(lean=12, flow=0.3, legs=crouch, smile=True, arms={'near': (40, 80), 'far': (-30, 10)},
             spear={'hand': 'near', 'angle': 25, 'up': 18, 'down': 18}, fx=[('dust', 12, 0, 3), ('dust', 38, 0, 3)]),
        base(lean=-2, head=-6, smile=True, glow=1, legs={'near': (14, 4), 'far': (-12, -4)},
             arms={'near': (120, 154), 'far': (172, 182)},
             spear={'hand': 'near', 'angle': 20, 'up': 10, 'down': 18}, fx=[('tipspark', 2)]),
    ]


ANIMS = {'IDLE': idle, 'WALK': walk, 'ATTACK': attack, 'CHEER': cheer, 'CELEBRATE': celebrate}

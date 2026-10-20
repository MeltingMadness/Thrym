"""Writes CompanionSprites.kt from the rig: palette plus run-length encoded frames."""
import sys
from preview import all_frames
from poses import ANIMS
from rig import W, H, K

OUT = sys.argv[1] if len(sys.argv) > 1 else "CompanionSprites.kt"
DUR = {
    'IDLE': [220] * 4,
    'WALK': [110] * 6,
    'ATTACK': [260, 90, 300, 120, 200],
    'CHEER': [160, 160, 160],
    'CELEBRATE': [200, 110, 220, 120, 160, 500],
}
SYMBOLS = [chr(c) for c in range(ord('A'), ord('Z') + 1)] + [chr(c) for c in range(ord('a'), ord('z') + 1)] + list("!#%&'()*+,-/:;<=>?@[]^_`{|}~")

F = all_frames()
for key, frames in F.items():
    assert len(frames) == len(DUR[key[1]]), key
colors = sorted({tuple(c) for fs in F.values() for g in fs for row in g for c in row if c},
                key=lambda c: (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2], c))
assert len(colors) <= len(SYMBOLS), len(colors)
sym = {c: SYMBOLS[i] for i, c in enumerate(colors)}


def encode(grid):
    flat = [sym[tuple(c)] if c else '.' for row in grid for c in row]
    out = []
    i = 0
    while i < len(flat):
        j = i
        while j < len(flat) and flat[j] == flat[i]: j += 1
        n = j - i
        out.append(flat[i] + (str(n) if n > 1 else ''))
        i = j
    return ''.join(out)


anchor = round(22 * K)
L = []
L.append('package com.thrym.shared.companion\n')
L.append('/**')
L.append(' * Pixel art of the companions: full figures on an %d x %d grid in a 16-bit style, facing right.' % (W, H))
L.append(' * Generated from the companion rig; change the rig and regenerate instead of editing frames.')
L.append(' *')
L.append(' * Every frame is one run-length encoded string, row by row from the top left: a symbol names a')
L.append(' * colour in [palette] (`.` is transparent) and an optional number repeats it. Both platforms')
L.append(' * draw the same pixels: Android with Compose, iOS from a bitmap built from [frame].')
L.append(' */')
L.append('object CompanionSprites {')
L.append('    const val SIZE: Int = %d\n' % W)
L.append('    /** Column of the figure\'s centre line in a frame. Mirroring around it turns the figure without a jump. */')
L.append('    const val ANCHOR_X: Int = %d\n' % anchor)
L.append('    /** Colours as ARGB, sorted from dark to light. */')
L.append('    val palette: Map<Char, Int> = mapOf(')
for i, c in enumerate(colors):
    s = sym[c]
    lit = "'\\''" if s == "'" else "'%s'" % s
    L.append('        %s to 0xFF%02X%02X%02X.toInt()%s' % (lit, c[0], c[1], c[2], ',' if i < len(colors) - 1 else ''))
L.append('    )\n')
L.append('    /** How long each frame of [animation] stays on screen, in milliseconds. */')
L.append('    fun durationsMs(animation: CompanionAnimation): List<Long> = when (animation) {')
for name, d in DUR.items():
    L.append('        CompanionAnimation.%s -> listOf(%s)' % (name, ', '.join('%dL' % x for x in d)))
L.append('    }\n')
L.append('    /** Number of frames of [animation]; the same for both characters. */')
L.append('    fun frameCount(animation: CompanionAnimation): Int = durationsMs(animation).size\n')
L.append('    /** One decoded frame, cached after the first use. */')
L.append('    fun frame(character: CompanionCharacter, animation: CompanionAnimation, index: Int): CompanionFrame {')
L.append('        val key = Triple(character, animation, index)')
L.append('        return cache.getOrPut(key) { CompanionFrame.decode(encoded(character, animation)[index], SIZE, SIZE, palette) }')
L.append('    }\n')
L.append('    private val cache = mutableMapOf<Triple<CompanionCharacter, CompanionAnimation, Int>, CompanionFrame>()\n')
L.append('    internal fun encoded(character: CompanionCharacter, animation: CompanionAnimation): List<String> = when (character) {')
for ch in ('ODIN', 'VALKYRIE'):
    L.append('        CompanionCharacter.%s -> when (animation) {' % ch)
    for name in DUR:
        L.append('            CompanionAnimation.%s -> %s_%s' % (name, ch, name))
    L.append('        }')
L.append('    }\n')
for (ch, name), frames in F.items():
    L.append('    private val %s_%s: List<String> = listOf(' % (ch, name))
    for i, g in enumerate(frames):
        L.append('        "%s"%s' % (encode(g), ',' if i < len(frames) - 1 else ''))
    L.append('    )\n')
L[-1] = L[-1].rstrip('\n')
L.append('}\n')
L.append('''/** One decoded frame: [argb] holds [width] x [height] colours row by row, 0 is transparent. */
class CompanionFrame(val width: Int, val height: Int, val argb: IntArray) {
    fun pixel(x: Int, y: Int): Int = argb[y * width + x]

    companion object {
        internal fun decode(data: String, width: Int, height: Int, palette: Map<Char, Int>): CompanionFrame {
            val argb = IntArray(width * height)
            var at = 0
            var i = 0
            while (i < data.length) {
                val symbol = data[i++]
                var count = 0
                while (i < data.length && data[i].isDigit()) count = count * 10 + (data[i++] - '0')
                if (count == 0) count = 1
                val colour = if (symbol == '.') 0 else palette.getValue(symbol)
                argb.fill(colour, at, at + count)
                at += count
            }
            require(at == argb.size) { "Frame has $at pixels instead of ${argb.size}" }
            return CompanionFrame(width, height, argb)
        }
    }
}
''')
open(OUT, 'w').write('\n'.join(L))
print(len(colors), 'colours', sum(len(encode(g)) for fs in F.values() for g in fs), 'chars')

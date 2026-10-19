package com.thrym.shared.companion

/**
 * Pixel art of the companions, drawn on a 32 x 32 grid in a 16-bit style. Each frame is a list of
 * rows; every character is one pixel and names a colour in [palette], `.` is transparent. Both
 * platforms draw the same pixels: Android with Compose, iOS from a bitmap built with [frame].
 *
 * The art is the single source of truth. Change a frame here, never in a platform copy.
 */
object CompanionSprites {
    const val SIZE: Int = 32

    /** Colours as ARGB. */
    val palette: Map<Char, Int> = mapOf(
        'K' to 0xFF1A1420.toInt(), // outline
        'O' to 0xFFFFFFFF.toInt(), // shine
        'F' to 0xFFFAFAFA.toInt(), // feather
        'f' to 0xFFBEC8D4.toInt(), // feather shade
        'H' to 0xFFA8B4C0.toInt(), // steel
        'h' to 0xFF6C7888.toInt(), // steel shade
        'G' to 0xFFF0C040.toInt(), // gold
        'g' to 0xFFB08020.toInt(), // gold shade
        'S' to 0xFFF0B890.toInt(), // skin
        's' to 0xFFC88860.toInt(), // skin shade
        'p' to 0xFFEC8C8C.toInt(), // blush
        'W' to 0xFFECE8E0.toInt(), // beard
        'w' to 0xFFB8B0A4.toInt(), // beard shade
        'B' to 0xFF3854A0.toInt(), // cloak
        'b' to 0xFF203070.toInt(), // cloak shade
        'N' to 0xFF6A4028.toInt(), // leather
        'L' to 0xFF8C5A32.toInt(), // wood
        'l' to 0xFF603C20.toInt(), // wood shade
        'Y' to 0xFFF8D860.toInt(), // hair
        'y' to 0xFFC8A030.toInt(), // hair shade
        'R' to 0xFFB03030.toInt(), // skirt
        'r' to 0xFF782020.toInt(), // skirt shade / mouth
    )

    /** The pixels of one frame as ARGB, row by row; transparent pixels are `0`. */
    fun frame(character: CompanionCharacter, pose: CompanionPose): CompanionFrame =
        frames.getValue(character to pose)

    /** The raw rows of one frame, for tests and tooling. */
    fun rows(character: CompanionCharacter, pose: CompanionPose): List<String> =
        rawFrames.getValue(character to pose)

    private val rawFrames: Map<Pair<CompanionCharacter, CompanionPose>, List<String>> = mapOf(
        (CompanionCharacter.ODIN to CompanionPose.IDLE) to ODIN_IDLE,
        (CompanionCharacter.ODIN to CompanionPose.BLINK) to ODIN_BLINK,
        (CompanionCharacter.ODIN to CompanionPose.CHEER) to ODIN_CHEER,
        (CompanionCharacter.VALKYRIE to CompanionPose.IDLE) to VALKYRIE_IDLE,
        (CompanionCharacter.VALKYRIE to CompanionPose.BLINK) to VALKYRIE_BLINK,
        (CompanionCharacter.VALKYRIE to CompanionPose.CHEER) to VALKYRIE_CHEER,
    )

    private val frames: Map<Pair<CompanionCharacter, CompanionPose>, CompanionFrame> by lazy {
        rawFrames.mapValues { (_, rows) -> CompanionFrame.fromRows(rows, palette) }
    }
}

/** One decoded frame: [argb] holds [width] x [height] pixels row by row, `0` is transparent. */
class CompanionFrame(val width: Int, val height: Int, val argb: IntArray) {
    fun pixel(x: Int, y: Int): Int = argb[y * width + x]

    companion object {
        internal fun fromRows(rows: List<String>, palette: Map<Char, Int>): CompanionFrame {
            val height = rows.size
            val width = rows.first().length
            val argb = IntArray(width * height)
            rows.forEachIndexed { y, row ->
                require(row.length == width) { "Row $y has ${row.length} pixels instead of $width" }
                row.forEachIndexed { x, char ->
                    argb[y * width + x] = if (char == '.') 0 else palette.getValue(char)
                }
            }
            return CompanionFrame(width, height, argb)
        }
    }
}

private val ODIN_IDLE = listOf(
    "............................K...",
    "...........................KOK..",
    ".....KK..................KKKHK..",
    "....KFFK.....KKKKKK.....KFKHHhK.",
    "....KFfFK..KKHHHHHHKK..KFfFKhK..",
    ".....KFfFKKHHHHHHHHHHKKFfFKKGK..",
    ".....KFFfKHHHHHHHHHHhhKfFFKKLK..",
    "......KFfKHHHHHHHHHHhhKfFK.KLK..",
    "......KKFKHHHHHHHHHhhhKFKK.KLK..",
    "........KGGGGGGGGGGGGGGK...KLK..",
    "........KgGgGgGgGgGgGgGK...KLK..",
    "........KSSSSSKSSSWWWWSK...KLK..",
    ".......KSSKKKKSSSSSKKSSSK..KLK..",
    ".......KSSKKKKSSSSSKKSSsK..KLK..",
    ".......KSKSKKSSssSSSSSSsK..KLK..",
    ".......KWSSSWWWWWWWWSSSWK..KLK..",
    ".......KWWSWWWwrrwWWWSWWK..KLK..",
    ".......KWWWWWWWWWWWWWWWWK..KLK..",
    ".......KWWWWwWWWWWWwWWWwK..KLK..",
    "......KBKWWWWWWWWWWWWWWKBKKKKK..",
    ".....KBBKwWWWWwWWWWWwWWKBBSSSK..",
    "....KSBBBKWWWWWWWWWWWWKBBBSsSK..",
    "....KSKBBBKwWWWWWWWWwKBBBKSsSK..",
    ".....KKBBBBKWWWWWWWWKBBBBKKKKK..",
    ".....KBBBBBBKwWWWWwKBBBBbbKKLK..",
    ".....KbBBBNNNKWWWWKNNNBBbbKKLK..",
    ".....KbBBBNGNNKKKKNNNNBBbbKKLK..",
    ".....KbbBBBBBBBBBBBBBBBbbbKKLK..",
    "......KbbBBBBBBBBBBBBBbbbK.KLK..",
    ".......KKNNNKKKKKKKKNNNKK..KLK..",
    ".......KNNNNK......KNNNNK..KLK..",
    "........KKKK........KKKK....K...",
)

private val ODIN_BLINK = listOf(
    "............................K...",
    "...........................KOK..",
    ".....KK..................KKKHK..",
    "....KFFK.....KKKKKK.....KFKHHhK.",
    "....KFfFK..KKHHHHHHKK..KFfFKhK..",
    ".....KFfFKKHHHHHHHHHHKKFfFKKGK..",
    ".....KFFfKHHHHHHHHHHhhKfFFKKLK..",
    "......KFfKHHHHHHHHHHhhKfFK.KLK..",
    "......KKFKHHHHHHHHHhhhKFKK.KLK..",
    "........KGGGGGGGGGGGGGGK...KLK..",
    "........KgGgGgGgGgGgGgGK...KLK..",
    "........KSSSSSKSSSWWWWSK...KLK..",
    ".......KSSKKKKSSSSSSSSSSK..KLK..",
    ".......KSSKKKKSSSSSKKSSsK..KLK..",
    ".......KSKSKKSSssSSSSSSsK..KLK..",
    ".......KWSSSWWWWWWWWSSSWK..KLK..",
    ".......KWWSWWWwrrwWWWSWWK..KLK..",
    ".......KWWWWWWWWWWWWWWWWK..KLK..",
    ".......KWWWWwWWWWWWwWWWwK..KLK..",
    "......KBKWWWWWWWWWWWWWWKBKKKKK..",
    ".....KBBKwWWWWwWWWWWwWWKBBSSSK..",
    "....KSBBBKWWWWWWWWWWWWKBBBSsSK..",
    "....KSKBBBKwWWWWWWWWwKBBBKSsSK..",
    ".....KKBBBBKWWWWWWWWKBBBBKKKKK..",
    ".....KBBBBBBKwWWWWwKBBBBbbKKLK..",
    ".....KbBBBNNNKWWWWKNNNBBbbKKLK..",
    ".....KbBBBNGNNKKKKNNNNBBbbKKLK..",
    ".....KbbBBBBBBBBBBBBBBBbbbKKLK..",
    "......KbbBBBBBBBBBBBBBbbbK.KLK..",
    ".......KKNNNKKKKKKKKNNNKK..KLK..",
    ".......KNNNNK......KNNNNK..KLK..",
    "........KKKK........KKKK....K...",
)

private val ODIN_CHEER = listOf(
    "............................K...",
    "...........................KOK..",
    ".....KK..................KKKHK..",
    "....KFFK.....KKKKKK.....KFKHHhK.",
    "....KFfFK..KKHHHHHHKK..KFfFKhK..",
    ".....KFfFKKHHHHHHHHHHKKFfFKKGK..",
    ".....KFFfKHHHHHHHHHHhhKfFFKKLK..",
    "......KFfKHHHHHHHHHHhhKfFK.KLK..",
    "......KKFKHHHHHHHHHhhhKFKK.KLK..",
    "........KGGGGGGGGGGGGGGK...KLK..",
    "........KgGgGgGgGgGgGgGK...KLK..",
    "........KSSSSSKSSSWWWWSK...KLK..",
    ".......KSSKKKKSSSSSKKSSSK..KLK..",
    ".......KSSKKKKSSSSKSSKSsK..KLK..",
    ".......KSKSKKSSssSSSSSSsK..KLK..",
    ".......KWSSSWWWWWWWWSSSWK..KLK..",
    ".......KWWSWWWwKKwWWWSWWK..KLK..",
    ".......KWWWWWWWWWWWWWWWWK..KLK..",
    ".......KWWWWwWWWWWWwWWWwK..KLK..",
    "......KBKWWWWWWWWWWWWWWKBKKKKK..",
    ".....KBBKwWWWWwWWWWWwWWKBBSSSK..",
    "....KSBBBKWWWWWWWWWWWWKBBBSsSK..",
    "....KSKBBBKwWWWWWWWWwKBBBKSsSK..",
    ".....KKBBBBKWWWWWWWWKBBBBKKKKK..",
    ".....KBBBBBBKwWWWWwKBBBBbbKKLK..",
    ".....KbBBBNNNKWWWWKNNNBBbbKKLK..",
    ".....KbBBBNGNNKKKKNNNNBBbbKKLK..",
    ".....KbbBBBBBBBBBBBBBBBbbbKKLK..",
    "......KbbBBBBBBBBBBBBBbbbK.KLK..",
    ".......KKNNNKKKKKKKKNNNKK..KLK..",
    ".......KNNNNK......KNNNNK..KLK..",
    "........KKKK........KKKK....K...",
)

private val VALKYRIE_IDLE = listOf(
    "............................K...",
    "...........................KOK..",
    ".....KK..................KKKHK..",
    "....KFFK.....KKKKKK.....KFKHHhK.",
    "....KFfFK..KKHHHHHHKK..KFfFKhK..",
    ".....KFfFKKHHHHHHHHHHKKFfFKKGK..",
    ".....KFFfKHHHHHHHHHHhhKfFFKKLK..",
    "......KFfKHHHHHHHHHHhhKfFK.KLK..",
    "......KKFKHHHHHHHHHhhhKFKK.KLK..",
    "........KGGGGGGGGGGGGGGK...KLK..",
    "........KgGgGgGgGgGgGgGK...KLK..",
    "........KYYYySSSSSSyYYYK...KLK..",
    "......KYKYSSKOSSSSKOSSYKYK.KLK..",
    "......KYKYSSKKSSSSKKSSYKYK.KLK..",
    "......KYKYSppSKSSKSppSYKYK.KLK..",
    "......KyKYSSSSSKKSSSSSYKyK.KLK..",
    "......KYKKYSSSSSSSSSSYKKYK.KLK..",
    "......KyK.KKSSSSSSSSKK.KyK.KLK..",
    "...KKKKKK..KKHHHHKK...KYYK.KLK..",
    "..KLLlLLKKHHHHGHHHHHK.KKKKKKKK..",
    ".KLLLlLLLKHHHGGGHHHhhhHHhhSSSK..",
    "KLLLLlLLLLKHHHGHHHHhhKHHhhSsSK..",
    "KlllGGGlllKHHHHHHHHhhhHHhhSsSK..",
    "KLLGgGGGLLKHHHHHHHHHhhKKKKKKKK..",
    "KlllGGGlllKGGGGGGGGGGGK....KLK..",
    "KLLLLlLLLLKRRrRRRRrRRrK....KLK..",
    ".KLLLlLLLKRRRrRRRRrRRrK....KLK..",
    "..KLLlLLKrrRRrrRRRrrRrK....KLK..",
    "...KKKKKKNNNKKKKKKNNNKK....KLK..",
    "........KNNNNK..KNNNNK.....KLK..",
    "........KKKKK....KKKKK.....KLK..",
    "............................K...",
)

private val VALKYRIE_BLINK = listOf(
    "............................K...",
    "...........................KOK..",
    ".....KK..................KKKHK..",
    "....KFFK.....KKKKKK.....KFKHHhK.",
    "....KFfFK..KKHHHHHHKK..KFfFKhK..",
    ".....KFfFKKHHHHHHHHHHKKFfFKKGK..",
    ".....KFFfKHHHHHHHHHHhhKfFFKKLK..",
    "......KFfKHHHHHHHHHHhhKfFK.KLK..",
    "......KKFKHHHHHHHHHhhhKFKK.KLK..",
    "........KGGGGGGGGGGGGGGK...KLK..",
    "........KgGgGgGgGgGgGgGK...KLK..",
    "........KYYYySSSSSSyYYYK...KLK..",
    "......KYKYSSSSSSSSSSSSYKYK.KLK..",
    "......KYKYSSKKSSSSKKSSYKYK.KLK..",
    "......KYKYSppSKSSKSppSYKYK.KLK..",
    "......KyKYSSSSSKKSSSSSYKyK.KLK..",
    "......KYKKYSSSSSSSSSSYKKYK.KLK..",
    "......KyK.KKSSSSSSSSKK.KyK.KLK..",
    "...KKKKKK..KKHHHHKK...KYYK.KLK..",
    "..KLLlLLKKHHHHGHHHHHK.KKKKKKKK..",
    ".KLLLlLLLKHHHGGGHHHhhhHHhhSSSK..",
    "KLLLLlLLLLKHHHGHHHHhhKHHhhSsSK..",
    "KlllGGGlllKHHHHHHHHhhhHHhhSsSK..",
    "KLLGgGGGLLKHHHHHHHHHhhKKKKKKKK..",
    "KlllGGGlllKGGGGGGGGGGGK....KLK..",
    "KLLLLlLLLLKRRrRRRRrRRrK....KLK..",
    ".KLLLlLLLKRRRrRRRRrRRrK....KLK..",
    "..KLLlLLKrrRRrrRRRrrRrK....KLK..",
    "...KKKKKKNNNKKKKKKNNNKK....KLK..",
    "........KNNNNK..KNNNNK.....KLK..",
    "........KKKKK....KKKKK.....KLK..",
    "............................K...",
)

private val VALKYRIE_CHEER = listOf(
    "............................K...",
    "...........................KOK..",
    ".....KK..................KKKHK..",
    "....KFFK.....KKKKKK.....KFKHHhK.",
    "....KFfFK..KKHHHHHHKK..KFfFKhK..",
    ".....KFfFKKHHHHHHHHHHKKFfFKKGK..",
    ".....KFFfKHHHHHHHHHHhhKfFFKKLK..",
    "......KFfKHHHHHHHHHHhhKfFK.KLK..",
    "......KKFKHHHHHHHHHhhhKFKK.KLK..",
    "........KGGGGGGGGGGGGGGK...KLK..",
    "........KgGgGgGgGgGgGgGK...KLK..",
    "........KYYYySSSSSSyYYYK...KLK..",
    "......KYKYSSKKSSSSKKSSYKYK.KLK..",
    "......KYKYSKSSKSSKSSKSYKYK.KLK..",
    "......KYKYSppSKSSKSppSYKYK.KLK..",
    "......KyKYSSSSSrrSSSSSYKyK.KLK..",
    "......KYKKYSSSSKKSSSSYKKYK.KLK..",
    "......KyK.KKSSSSSSSSKK.KyK.KLK..",
    "...KKKKKK..KKHHHHKK...KYYK.KLK..",
    "..KLLlLLKKHHHHGHHHHHK.KKKKKKKK..",
    ".KLLLlLLLKHHHGGGHHHhhhHHhhSSSK..",
    "KLLLLlLLLLKHHHGHHHHhhKHHhhSsSK..",
    "KlllGGGlllKHHHHHHHHhhhHHhhSsSK..",
    "KLLGgGGGLLKHHHHHHHHHhhKKKKKKKK..",
    "KlllGGGlllKGGGGGGGGGGGK....KLK..",
    "KLLLLlLLLLKRRrRRRRrRRrK....KLK..",
    ".KLLLlLLLKRRRrRRRRrRRrK....KLK..",
    "..KLLlLLKrrRRrrRRRrrRrK....KLK..",
    "...KKKKKKNNNKKKKKKNNNKK....KLK..",
    "........KNNNNK..KNNNNK.....KLK..",
    "........KKKKK....KKKKK.....KLK..",
    "............................K...",
)

package app.chocola.data

import java.io.File
import java.io.RandomAccessFile

/** Read dimensions once on the IO thread, so restored image messages never resize after decoding. */
internal object ImageDimensions {
    fun read(file: File, mime: String): Pair<Int, Int> = runCatching {
        RandomAccessFile(file, "r").use { input ->
            fun little(count: Int): Int { var result = 0; repeat(count) { result = result or (input.readUnsignedByte() shl (it * 8)) }; return result }
            when (mime) {
                "image/png" -> { input.seek(16); input.readInt() to input.readInt() }
                "image/jpeg" -> {
                    input.seek(2)
                    var dimensions = 0 to 0
                    while (input.filePointer < minOf(input.length(), 512L * 1024)) {
                        if (input.readUnsignedByte() != 0xff) break
                        var marker = input.readUnsignedByte()
                        while (marker == 0xff) marker = input.readUnsignedByte()
                        if (marker == 0xda || marker == 0xd9) break
                        if (marker in 0xd0..0xd7 || marker == 0x01) continue
                        val length = input.readUnsignedShort()
                        if (length < 2 || input.filePointer + length - 2 > input.length()) break
                        if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                            input.readUnsignedByte(); val height = input.readUnsignedShort(); dimensions = input.readUnsignedShort() to height; break
                        }
                        input.seek(input.filePointer + length - 2)
                    }
                    dimensions
                }
                "image/webp" -> {
                    input.seek(12); val code = ByteArray(4); input.readFully(code); input.skipBytes(4)
                    when (String(code, Charsets.US_ASCII)) {
                        "VP8X" -> { input.skipBytes(4); (little(3) + 1) to (little(3) + 1) }
                        "VP8L" -> { require(input.readUnsignedByte() == 0x2f); val bits = little(4); ((bits and 0x3fff) + 1) to (((bits ushr 14) and 0x3fff) + 1) }
                        "VP8 " -> { input.skipBytes(6); (little(2) and 0x3fff) to (little(2) and 0x3fff) }
                        else -> 0 to 0
                    }
                }
                else -> 0 to 0
            }
        }
    }.getOrDefault(0 to 0).takeIf { (width, height) -> width in 1..32768 && height in 1..32768 && width.toLong() * height <= 268_435_456 } ?: (0 to 0)
}

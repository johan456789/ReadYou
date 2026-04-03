package me.ash.reader.infrastructure.coil
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import coil.size.Size
import okio.BufferedSource

/**
 * Decodes ICO favicons served by FreshRSS proxy endpoints.
 */
class IcoDecoder(
    private val sourceResult: SourceResult,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val imageSource = sourceResult.source
        val source = imageSource.source()
        val image = selectBestEntry(source.peek(), options.size)

        source.skip(image.offset.toLong())
        val imageBytes = source.readByteArray(image.size.toLong())

        val bitmap =
            requireNotNull(
                BitmapFactory.decodeByteArray(
                    imageBytes,
                    0,
                    imageBytes.size,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = options.config
                    },
                )
            ) { "Failed to decode ICO image" }

        return DecodeResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = false,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val source = result.source.source().peek()
            return if (supportsDecode(source, options.size)) {
                IcoDecoder(result, options)
            } else {
                null
            }
        }
    }
}

private data class IconDirEntry(
    val width: Byte,
    val height: Byte,
    val numColors: Byte,
    val colorPlanes: Short,
    val bytesPerPixel: Short,
    val size: Int,
    val offset: Int,
) {
    val widthPixels: Int
        get() = width.toUnsignedDimension()

    val heightPixels: Int
        get() = height.toUnsignedDimension()
}

private fun isIco(source: BufferedSource): Boolean {
    val peek = source.peek()
    return runCatching {
        peek.readShortLe() == 0.toShort() &&
            peek.readShortLe() == 1.toShort() &&
            peek.readShortLe().toInt() in 1..256
    }.getOrDefault(false)
}

private fun supportsDecode(
    source: BufferedSource,
    preferredSize: Size,
): Boolean {
    if (!isIco(source)) return false

    return runCatching {
        val peek = source.peek()
        val image = selectBestEntry(peek, preferredSize)
        peek.skip(image.offset.toLong())
        // We only claim ICO payloads when the selected entry is PNG. BMP/DIB entries are left to
        // Coil's normal failure path so ReadYou avoids crashing on unsupported ICO variants.
        peek.readByteArray(image.size.toLong()).hasPngHeader()
    }.getOrDefault(false)
}

private fun selectBestEntry(
    source: BufferedSource,
    preferredSize: Size,
): IconDirEntry {
    val peek = source.peek()
    peek.skip(4)
    val numImages = peek.readShortLe().toInt()
    var image = parseEntry(peek)
    val preferredWidth = (preferredSize.width as? Dimension.Pixels)?.px
    val preferredHeight = (preferredSize.height as? Dimension.Pixels)?.px

    repeat(numImages - 1) {
        if (
            preferredWidth != null &&
                preferredHeight != null &&
                image.widthPixels >= preferredWidth &&
                image.heightPixels >= preferredHeight
        ) {
            return image
        }

        val currentImage = parseEntry(peek)
        if (currentImage.widthPixels * currentImage.heightPixels >
            image.widthPixels * image.heightPixels
        ) {
            image = currentImage
        }
    }

    return image
}

private fun parseEntry(source: BufferedSource): IconDirEntry {
    val width = source.readByte()
    val height = source.readByte()
    val numColors = source.readByte()
    source.skip(1)
    val colorPlanes = source.readShortLe()
    val bytesPerPixel = source.readShortLe()
    val size = source.readIntLe()
    val offset = source.readIntLe()
    return IconDirEntry(width, height, numColors, colorPlanes, bytesPerPixel, size, offset)
}

private fun ByteArray.hasPngHeader(): Boolean =
    size > 4 &&
        this[0] == 0x89.toByte() &&
        this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() &&
        this[3] == 0x47.toByte()

private fun Byte.toUnsignedDimension(): Int {
    val value = toInt() and 0xFF
    return if (value == 0) 256 else value
}

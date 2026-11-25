package com.canon.multicam.protocol.ptpip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LiveViewDatasetParser {
    private const val MIN_DATASET_BYTES = 8
    private const val CHUNK_HEADER_BYTES = 8
    private const val CHUNK_TYPE_JPEG = 0x00000001
    private const val MAX_REASONABLE_CHUNK_BYTES = 8 * 1024 * 1024
    private const val JPEG_SOI_BYTE_1 = 0xFF
    private const val JPEG_SOI_BYTE_2 = 0xD8
    private const val JPEG_EOI_BYTE_1 = 0xFF
    private const val JPEG_EOI_BYTE_2 = 0xD9
    private const val BYTE_MASK = 0xFF

    fun extractJpegFrame(datasetBytes: ByteArray): Bitmap? {
        if (datasetBytes.size < MIN_DATASET_BYTES) return null
        val buf = ByteBuffer.wrap(datasetBytes).order(ByteOrder.LITTLE_ENDIAN)

        while (buf.remaining() >= CHUNK_HEADER_BYTES) {
            val chunkSize = buf.int
            val chunkType = buf.int
            val payloadSize = chunkSize - CHUNK_HEADER_BYTES
            if (payloadSize <= 0 || payloadSize > buf.remaining()) break
            if (payloadSize > MAX_REASONABLE_CHUNK_BYTES) break

            if (chunkType == CHUNK_TYPE_JPEG) {
                val jpegBytes = ByteArray(payloadSize)
                buf.get(jpegBytes)
                return decodeBitmapSafe(jpegBytes)
            } else {
                buf.position(buf.position() + payloadSize)
            }
        }
        return fallbackScanJpegMarkers(datasetBytes)
    }

    private fun fallbackScanJpegMarkers(raw: ByteArray): Bitmap? {
        var soiIndex = -1
        for (i in 0 until raw.size - 1) {
            val b0 = raw[i].toInt() and BYTE_MASK
            val b1 = raw[i + 1].toInt() and BYTE_MASK
            if (b0 == JPEG_SOI_BYTE_1 && b1 == JPEG_SOI_BYTE_2) {
                soiIndex = i
                break
            }
        }
        if (soiIndex < 0) return null

        var eoiIndex = -1
        for (i in raw.size - 2 downTo soiIndex + 2) {
            val b0 = raw[i].toInt() and BYTE_MASK
            val b1 = raw[i + 1].toInt() and BYTE_MASK
            if (b0 == JPEG_EOI_BYTE_1 && b1 == JPEG_EOI_BYTE_2) {
                eoiIndex = i + 2
                break
            }
        }
        if (eoiIndex <= soiIndex) return null
        return BitmapFactory.decodeByteArray(raw, soiIndex, eoiIndex - soiIndex)
    }

    private fun decodeBitmapSafe(jpegBytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
    }
}

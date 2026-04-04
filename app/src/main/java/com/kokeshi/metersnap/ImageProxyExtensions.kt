package com.kokeshi.metersnap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun ImageProxy.toBitmapCompat(): Bitmap? {
    if (format != ImageFormat.YUV_420_888 || planes.size < 3) return null

    val nv21 = yuv420888ToNv21()
    if (nv21.isEmpty()) return null

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

private fun ImageProxy.yuv420888ToNv21(): ByteArray {
    val imageWidth = width
    val imageHeight = height
    if (imageWidth <= 0 || imageHeight <= 0) return ByteArray(0)

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val out = ByteArray(imageWidth * imageHeight * 3 / 2)
    var offset = 0

    val yBuffer = yPlane.buffer.duplicate()
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    val yRow = ByteArray(yRowStride)

    for (row in 0 until imageHeight) {
        val rowStart = row * yRowStride
        if (rowStart >= yBuffer.capacity()) break

        val length = if (yPixelStride == 1) {
            imageWidth
        } else {
            (imageWidth - 1) * yPixelStride + 1
        }

        val safeLength = minOf(length, yBuffer.capacity() - rowStart)
        yBuffer.position(rowStart)
        yBuffer.get(yRow, 0, safeLength)

        if (yPixelStride == 1) {
            val copySize = minOf(imageWidth, safeLength)
            System.arraycopy(yRow, 0, out, offset, copySize)
            if (copySize < imageWidth) {
                java.util.Arrays.fill(out, offset + copySize, offset + imageWidth, 0.toByte())
            }
            offset += imageWidth
        } else {
            for (col in 0 until imageWidth) {
                val index = col * yPixelStride
                out[offset++] = if (index < safeLength) yRow[index] else 0
            }
        }
    }

    val uBuffer = uPlane.buffer.duplicate()
    val vBuffer = vPlane.buffer.duplicate()
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride

    val chromaHeight = imageHeight / 2
    val chromaWidth = imageWidth / 2
    val uRow = ByteArray(uRowStride)
    val vRow = ByteArray(vRowStride)

    for (row in 0 until chromaHeight) {
        val uRowStart = row * uRowStride
        val vRowStart = row * vRowStride
        if (uRowStart >= uBuffer.capacity() || vRowStart >= vBuffer.capacity()) break

        val uLength = if (uPixelStride == 1) {
            chromaWidth
        } else {
            (chromaWidth - 1) * uPixelStride + 1
        }
        val vLength = if (vPixelStride == 1) {
            chromaWidth
        } else {
            (chromaWidth - 1) * vPixelStride + 1
        }

        val safeULength = minOf(uLength, uBuffer.capacity() - uRowStart)
        val safeVLength = minOf(vLength, vBuffer.capacity() - vRowStart)

        uBuffer.position(uRowStart)
        uBuffer.get(uRow, 0, safeULength)
        vBuffer.position(vRowStart)
        vBuffer.get(vRow, 0, safeVLength)

        for (col in 0 until chromaWidth) {
            val vIndex = col * vPixelStride
            val uIndex = col * uPixelStride
            out[offset++] = if (vIndex < safeVLength) vRow[vIndex] else 0
            out[offset++] = if (uIndex < safeULength) uRow[uIndex] else 0
        }
    }

    return out
}


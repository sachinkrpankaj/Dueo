package com.example.dueo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

data class SquareCrop(val left: Int, val top: Int, val side: Int)
fun squareCrop(width: Int, height: Int, zoom: Float, centerX: Float, centerY: Float): SquareCrop {
    require(width > 0 && height > 0)
    val side = (min(width, height) / zoom.coerceIn(1f, 5f)).roundToInt().coerceIn(1, min(width, height))
    val left = (centerX * width - side / 2f).roundToInt().coerceIn(0, width - side)
    val top = (centerY * height - side / 2f).roundToInt().coerceIn(0, height - side)
    return SquareCrop(left, top, side)
}

fun loadPhoto(context: Context, uri: Uri): Bitmap {
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val ratio = 1600f / maxOf(info.size.width, info.size.height)
            if (ratio < 1) decoder.setTargetSize((info.size.width * ratio).roundToInt().coerceAtLeast(1), (info.size.height * ratio).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported photo" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    val bitmap = context.contentResolver.openInputStream(uri).use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("Cannot read photo")
    val orientation = context.contentResolver.openInputStream(uri).use { stream ->
        stream?.let { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

fun saveCrop(context: Context, bitmap: Bitmap, crop: SquareCrop): String {
    val result = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    Canvas(result).apply {
        drawColor(android.graphics.Color.WHITE)
        drawBitmap(bitmap, Rect(crop.left, crop.top, crop.left + crop.side, crop.top + crop.side), Rect(0, 0, 512, 512), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
    val file = File(context.cacheDir, "person-crop-${UUID.randomUUID()}.jpg")
    file.outputStream().use { check(result.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
    return file.absolutePath
}

package io.github.i2996.sleepcover

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.util.DisplayMetrics
import android.view.Display
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 표지를 화면 크기에 맞춰 그려서 슬립 이미지 파일로 저장한다. */
object CoverWriter {
    const val FILE_NAME = "sleep_cover.png"

    fun outputFile(ctx: Context): File =
        File(File(Environment.getExternalStorageDirectory(), Prefs.folder(ctx)), FILE_NAME)

    fun canWrite(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    fun apply(ctx: Context, coverUrl: String, title: String): File {
        if (!canWrite(ctx)) throw IOException("저장 권한(모든 파일 접근)이 없어요")
        val src = download(coverUrl)
        val (w, h) = screenSize(ctx)
        val bmp = compose(src, w, h, Prefs.fill(ctx), Prefs.gray(ctx))
        val file = outputFile(ctx)
        val dir = file.parentFile!!
        if (!dir.exists() && !dir.mkdirs()) throw IOException("폴더를 만들 수 없어요: ${dir.path}")
        // 같은 파일을 그대로 덮어써서, 기기 설정에서 한 번 고른 이미지가 계속 바뀌게 한다.
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        MediaScannerConnection.scanFile(
            ctx.applicationContext, arrayOf(file.absolutePath), arrayOf("image/png"), null
        )
        Prefs.setApplied(ctx, title)
        return file
    }

    private fun download(url: String): Bitmap {
        var last: Exception? = null
        for (u in listOf(CoverSearch.bigCover(url), url).distinct()) {
            try {
                val b = Http.bytes(u)
                BitmapFactory.decodeByteArray(b, 0, b.size)?.let { return it }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("표지 이미지를 받지 못했어요" + (last?.message?.let { " ($it)" } ?: ""))
    }

    /** 세로 방향 기준 (가로, 세로) 실제 화면 픽셀. */
    private fun screenSize(ctx: Context): Pair<Int, Int> {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        val a = m.widthPixels
        val b = m.heightPixels
        return minOf(a, b) to maxOf(a, b)
    }

    fun compose(src: Bitmap, w: Int, h: Int, fill: Boolean, gray: Boolean): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val sx = w.toFloat() / src.width
        val sy = h.toFloat() / src.height
        val s = if (fill) maxOf(sx, sy) else minOf(sx, sy)
        val dw = src.width * s
        val dh = src.height * s
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (gray) p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        c.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), p)
        return out
    }
}

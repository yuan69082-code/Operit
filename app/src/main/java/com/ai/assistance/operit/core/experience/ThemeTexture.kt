package com.ai.assistance.operit.core.experience

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** Deterministic procedural materials rendered through the app's existing image background. */
object ThemeTexture {
    fun create(context: Context, family: String, hue: Float, seed: Int, colors: Int, mood: Float,
               starOpacity: Float, starSize: Float, starGlow: Float, warmth: Float): String {
        require(colors in 1..6 && mood in 0f..1f && starOpacity in 0f..1f && starSize in 0f..10f && starGlow in 0f..30f && warmth in 0f..1f)
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG); val random = Random(seed)
            val dark = family == "墨色" || family == "暗夜"
            fun color(offset: Float, saturation: Float, value: Float) = Color.HSVToColor(floatArrayOf(((hue + offset) % 360 + 360) % 360, saturation, value))
            val palette = IntArray(colors.coerceAtLeast(2)) { index ->
                color(if (colors == 1) 0f else index * 35f, .06f + mood * .22f,
                    if (dark) .08f + index * .025f else .97f - index * .015f)
            }
            paint.shader = LinearGradient(0f, 0f, 720f, 1280f, palette, null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 720f, 1280f, paint); paint.shader = null
            when (family) {
                "纸张" -> repeat(18000) { paint.color = Color.argb(random.nextInt(4, 20), 95, 75, 45); canvas.drawPoint(random.nextFloat() * 720, random.nextFloat() * 1280, paint) }
                "织物" -> {
                    paint.strokeWidth = 1f; paint.color = Color.argb(20, 80, 70, 65)
                    for (x in 0..720 step 4) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 1280f, paint)
                    for (y in 0..1280 step 4) canvas.drawLine(0f, y.toFloat(), 720f, y.toFloat(), paint)
                }
                "雾玻璃", "糖果" -> repeat(10) {
                    paint.color = color(it * 35f, .2f + mood * .2f, 1f); paint.alpha = if (family == "雾玻璃") 30 else 70
                    paint.maskFilter = BlurMaskFilter(if (family == "雾玻璃") 60f else 12f, BlurMaskFilter.Blur.NORMAL)
                    canvas.drawCircle(random.nextFloat() * 720, random.nextFloat() * 1280, random.nextFloat() * 120 + 40, paint)
                }
                "墨色" -> repeat(12) {
                    paint.color = Color.argb(30, 150, 155, 165); paint.maskFilter = BlurMaskFilter(35f, BlurMaskFilter.Blur.NORMAL)
                    canvas.drawOval(random.nextFloat() * 500, random.nextFloat() * 900, 720f, 1280f, paint)
                }
                "暗夜" -> {
                    paint.color = Color.argb(25, 130, 180, 200); paint.strokeWidth = 1f
                    for (x in 0..720 step 60) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 1280f, paint)
                    for (y in 0..1280 step 60) canvas.drawLine(0f, y.toFloat(), 720f, y.toFloat(), paint)
                }
            }
            paint.maskFilter = null
            if (starOpacity > 0 && starSize > 0) repeat(36) {
                val x = random.nextFloat() * 720; val y = random.nextFloat() * 1280
                paint.color = Color.argb((starOpacity * 255).toInt(), 255, (255 - warmth * 35).toInt(), (255 - warmth * 100).toInt())
                if (starGlow > 0) { paint.maskFilter = BlurMaskFilter(starGlow, BlurMaskFilter.Blur.NORMAL); canvas.drawCircle(x, y, starSize * 1.5f, paint); paint.maskFilter = null }
                paint.strokeWidth = 1.5f; canvas.drawLine(x - starSize, y, x + starSize, y, paint); canvas.drawLine(x, y - starSize, x, y + starSize, paint)
            }
            val directory = File(context.filesDir, "theme_studio_assets").apply { mkdirs() }
            val file = File(directory, "${UUID.randomUUID()}.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return Uri.fromFile(file).toString()
        } finally { bitmap.recycle() }
    }
}

package com.iykyk.collageapp.collage

import android.graphics.*
import com.iykyk.collageapp.pipeline.PersonResult
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Builds a single shareable collage bitmap: a dark, Instagram-Story-style
 * grid ("cast of characters") with one rounded tile per distinct person,
 * a center-cropped generous shot of them, and their appearance count.
 */
object CollageGenerator {

    fun render(
        people: List<PersonResult>,
        title: String,
        canvasWidth: Int = 1080,
        canvasHeight: Int = 1920
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawBackground(canvas, canvasWidth, canvasHeight)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            textSize = 34f
            textAlign = Paint.Align.CENTER
        }

        val topPadding = 140f
        canvas.drawText(title, canvasWidth / 2f, topPadding, titlePaint)
        canvas.drawText(
            "${people.size} ${if (people.size == 1) "person" else "people"} detected",
            canvasWidth / 2f, topPadding + 50f, subtitlePaint
        )

        val gridTop = topPadding + 110f
        val gridBottom = canvasHeight - 100f
        val gridPadding = 36f

        val cols = columnsFor(people.size)
        val rows = ceil(people.size / cols.toFloat()).toInt().coerceAtLeast(1)

        val cellW = (canvasWidth - gridPadding * (cols + 1)) / cols
        val cellH = (gridBottom - gridTop - gridPadding * (rows + 1)) / rows

        val corner = 32f
        people.forEachIndexed { i, person ->
            val row = i / cols
            val col = i % cols
            val left = gridPadding + col * (cellW + gridPadding)
            val top = gridTop + gridPadding + row * (cellH + gridPadding)
            val rect = RectF(left, top, left + cellW, top + cellH)
            drawTile(canvas, rect, corner, person)
        }

        return bitmap
    }

    private fun columnsFor(count: Int): Int = when {
        count <= 1 -> 1
        count <= 4 -> 2
        count <= 9 -> 3
        else -> 4
    }

    private fun drawBackground(canvas: Canvas, w: Int, h: Int) {
        val gradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.rgb(28, 20, 45), Color.rgb(12, 10, 22)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = gradient })
    }

    private fun drawTile(canvas: Canvas, rect: RectF, corner: Float, person: PersonResult) {
        val path = Path().apply { addRoundRect(rect, corner, corner, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)

        // center-crop the representative bitmap to fill the tile without distortion
        val src = person.representative
        val srcAspect = src.width.toFloat() / src.height
        val dstAspect = rect.width() / rect.height()
        val srcRect = if (srcAspect > dstAspect) {
            val newWidth = (src.height * dstAspect).toInt()
            val xOffset = (src.width - newWidth) / 2
            Rect(xOffset, 0, xOffset + newWidth, src.height)
        } else {
            val newHeight = (src.width / dstAspect).toInt()
            val yOffset = (src.height - newHeight) / 2
            Rect(0, yOffset, src.width, yOffset + newHeight)
        }
        canvas.drawBitmap(src, srcRect, rect, null)

        // bottom gradient scrim for legible label text
        val scrimHeight = rect.height() * 0.32f
        val scrim = LinearGradient(
            0f, rect.bottom - scrimHeight, 0f, rect.bottom,
            intArrayOf(Color.TRANSPARENT, Color.argb(190, 0, 0, 0)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect.left, rect.bottom - scrimHeight, rect.right, rect.bottom, Paint().apply { shader = scrim })

        canvas.restore()

        // border
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.argb(90, 255, 255, 255)
        })

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = rect.width() * 0.09f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255)
            textSize = rect.width() * 0.075f
        }

        val label = "Person ${person.personIndex + 1}"
        val countLabel = "${person.appearanceCount}x appearance${if (person.appearanceCount == 1) "" else "s"}"

        canvas.drawText(label, rect.left + 24f, rect.bottom - 54f, labelPaint)
        canvas.drawText(countLabel, rect.left + 24f, rect.bottom - 20f, countPaint)
    }
}

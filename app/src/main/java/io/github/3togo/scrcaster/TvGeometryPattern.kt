package io.github.3togo.scrcaster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/** Uses equal horizontal/vertical pixel distances, independent of stream geometry. */
internal class TvGeometryPattern(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    init {
        contentDescription = context.getString(R.string.tv_geometry_description)
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(18, 27, 43))
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.4f
        paint.color = Color.WHITE
        canvas.drawRect(cx - radius, cy - radius, cx + radius, cy + radius, paint)
        paint.color = Color.rgb(36, 215, 196)
        canvas.drawCircle(cx, cy, radius, paint)
    }
}

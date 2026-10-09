package com.swarmos.app.pdu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmShape

/** Line icons the kit's SwarmIcon set does not have. Same 1.5 dp stroke family, drawn on a 24 unit grid. */
enum class PduIcon { Photo, Folder, Scan, Lock, Camera, Mic }

@Composable
fun PduGlyph(icon: PduIcon, modifier: Modifier = Modifier, tint: Color = SwarmColors.ink, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        fun sz(w: Float, h: Float) = Size(w * u, h * u)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(tint, o(x0, y0), o(x1, y1), stroke.width, StrokeCap.Round)
        fun poly(vararg pts: Float, close: Boolean = false) {
            val p = Path().apply {
                moveTo(pts[0] * u, pts[1] * u)
                for (i in 2 until pts.size step 2) lineTo(pts[i] * u, pts[i + 1] * u)
                if (close) close()
            }
            drawPath(p, tint, style = stroke)
        }
        when (icon) {
            PduIcon.Photo -> {
                drawRoundRect(tint, o(3f, 4f), sz(18f, 16f), CornerRadius(2f * u), style = stroke)
                drawCircle(tint, 1.6f * u, o(9f, 10f), style = stroke)
                poly(3f, 18f, 9f, 13f, 13f, 16.5f, 16f, 13.5f, 21f, 18f)
            }
            PduIcon.Folder -> poly(3f, 7f, 9f, 7f, 11f, 9.5f, 21f, 9.5f, 21f, 19f, 3f, 19f, close = true)
            PduIcon.Scan -> {
                poly(4f, 8f, 4f, 4f, 8f, 4f)
                poly(16f, 4f, 20f, 4f, 20f, 8f)
                poly(20f, 16f, 20f, 20f, 16f, 20f)
                poly(8f, 20f, 4f, 20f, 4f, 16f)
                line(8f, 10f, 16f, 10f)
                line(8f, 14f, 14f, 14f)
            }
            PduIcon.Lock -> {
                drawRoundRect(tint, o(5f, 11f), sz(14f, 9f), CornerRadius(2f * u), style = stroke)
                val shackle = Path().apply {
                    moveTo(8f * u, 11f * u)
                    lineTo(8f * u, 8f * u)
                    arcTo(androidx.compose.ui.geometry.Rect(o(8f, 4f), sz(8f, 8f)), 180f, 180f, false)
                    lineTo(16f * u, 11f * u)
                }
                drawPath(shackle, tint, style = stroke)
                line(12f, 14.5f, 12f, 16.5f)
            }
            PduIcon.Camera -> {
                drawRoundRect(tint, o(3f, 7f), sz(18f, 12f), CornerRadius(2f * u), style = stroke)
                poly(8f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 16f, 7f)
                drawCircle(tint, 3.2f * u, o(12f, 13f), style = stroke)
            }
            PduIcon.Mic -> {
                drawRoundRect(tint, o(9f, 3f), sz(6f, 11f), CornerRadius(3f * u), style = stroke)
                val cradle = Path().apply {
                    moveTo(18.5f * u, 11.5f * u)
                    arcTo(androidx.compose.ui.geometry.Rect(o(5.5f, 5f), sz(13f, 13f)), 0f, 180f, false)
                }
                drawPath(cradle, tint, style = stroke)
                line(12f, 18.5f, 12f, 21f)
                line(9f, 21f, 15f, 21f)
            }
        }
    }
}

/** An icon in a 40 dp outlined square, the leading element of a source or folder row. */
@Composable
fun PduIconBox(icon: PduIcon, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).border(1.5.dp, SwarmColors.ink, SwarmShape), contentAlignment = Alignment.Center) {
        PduGlyph(icon)
    }
}

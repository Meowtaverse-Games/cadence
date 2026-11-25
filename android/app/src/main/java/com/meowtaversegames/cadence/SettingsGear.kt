package com.meowtaversegames.cadence

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// Original Cadence geometry, generated from eight radial teeth; no third-party icon asset.
@Composable
fun SettingsGear(onClick: () -> Unit) {
    val colors = LocalHomeColors.current
    val path = remember {
        Path().apply {
            fillType = Path.FillType.EVEN_ODD
            repeat(32) { point ->
                val angle = point * Math.PI / 16 - Math.PI / 2
                val radius = if (point % 4 in 1..2) 11.0 else 8.5
                val x = (12 + radius * cos(angle)).toFloat()
                val y = (12 + radius * sin(angle)).toFloat()
                if (point == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            addCircle(12f, 12f, 3.8f, Path.Direction.CW)
        }
    }
    val paint = remember(colors) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.text.toArgb()
            setShadowLayer(4f, 0f, 1f, colors.opposite.copy(alpha = .85f).toArgb())
        }
    }
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = "設定" }) {
        Canvas(Modifier.size(24.dp)) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val saved = native.save()
                native.scale(size.width / 24f, size.height / 24f)
                native.drawPath(path, paint)
                native.restoreToCount(saved)
            }
        }
    }
}

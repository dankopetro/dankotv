package tv.danko.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Estrella DIBUJADA (rellena si fav, contorno si no): los glifos ★/☆ no los
 * dibuja la fuente de algunos teléfonos y el botón quedaba vacío (verificado
 * en device 16-17/09). Se usa en el player y en las filas de canales.
 */
@Composable
fun StarIcon(
    filled: Boolean,
    tint: Color = LocalContentColor.current,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.then(Modifier.size(20.dp))) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outer = size.minDimension / 2f
        val inner = outer * 0.382f
        val path = Path().apply {
            for (i in 0 until 10) {
                val ang = -Math.PI / 2 + i * Math.PI / 5
                val rad = if (i % 2 == 0) outer else inner
                val x = (cx + rad * kotlin.math.cos(ang)).toFloat()
                val y = (cy + rad * kotlin.math.sin(ang)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        if (filled) {
            drawPath(path, tint)
        } else {
            drawPath(path, tint, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

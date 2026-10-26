package com.grulla.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val neck = PathParser().parsePathString("M31 46 C 32.5 32, 29 22, 35.5 13").toPath()
private val head = PathParser().parsePathString("M35 13.5 C 37 9, 43 8.5, 46 11.5 L 55 13.5 L 46 15 C 42 16, 37.5 16, 35 13.5 Z").toPath()

/**
 * The Grulla mark: a note whose stem becomes a crane's neck, with the red crown.
 * Same geometry (64-unit box) as the launcher icon.
 */
@Composable
fun CraneMark(modifier: Modifier = Modifier, size: Dp = 32.dp, ink: Color = Grulla.colors.ink, crown: Color = Grulla.colors.crown) {
    Canvas(modifier.size(size)) {
        scale(this.size.width / 64f, pivot = Offset.Zero) {
            rotate(-22f, pivot = Offset(21f, 49f)) {
                drawOval(ink, topLeft = Offset(10f, 41.5f), size = Size(22f, 15f))
            }
            drawPath(neck, ink, style = Stroke(width = 4f, cap = StrokeCap.Round))
            drawPath(head, ink)
            drawCircle(crown, radius = 3.4f, center = Offset(41f, 8.6f))
        }
    }
}

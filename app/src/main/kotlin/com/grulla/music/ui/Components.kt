package com.grulla.music.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Pill toggle: neutral when off, indigo ("you") when on. */
@Composable
fun GrullaChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val c = Grulla.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (selected) c.indigoTint else Color.Transparent,
        contentColor = if (selected) c.onIndigoTint else c.ink,
        border = BorderStroke(1.dp, if (selected) c.indigo else c.lineStrong),
        modifier = modifier
            .height(36.dp)
            .semantics {
                role = Role.Switch
                this.selected = selected
            },
    ) {
        Row(
            Modifier.padding(start = if (leadingIcon != null) 10.dp else 12.dp, end = if (trailing != null) 6.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                label,
                style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            trailing?.invoke()
        }
    }
}

/** Small read-only tag, e.g. "Tempo 80%". [you] uses the indigo tint. */
@Composable
fun Tag(text: String, you: Boolean = false) {
    val c = Grulla.colors
    Surface(shape = CircleShape, color = if (you) c.indigoTint else c.tag, contentColor = if (you) c.onIndigoTint else c.ink) {
        Text(
            text,
            style = if (you) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Uppercase section label. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Grulla.colors.graphite, modifier = modifier)
}

/** Filled ink button (main action that is not "sound"). */
@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Grulla.colors
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.paper),
        contentPadding = PaddingValues(horizontal = 22.dp),
    ) { ButtonContent(text, icon) }
}

/** Filled crown-red button: starts sound ("Continue"). */
@Composable
fun CrownButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = AppIcons.Play) {
    val c = Grulla.colors
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.crown, contentColor = c.onCrown),
    ) { ButtonContent(text, icon) }
}

/** Filled indigo button ("you" actions, e.g. Stop listening). */
@Composable
fun IndigoButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Grulla.colors
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.indigo, contentColor = c.onIndigo),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val c = Grulla.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        border = BorderStroke(1.dp, c.lineStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) { ButtonContent(text, icon) }
}

@Composable
private fun RowScope.ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Box(Modifier.size(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
}

/** Round icon button with a hairline border (Stop, page arrows). */
@Composable
fun RoundOutlineButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, size: Dp = 48.dp, enabled: Boolean = true) {
    val c = Grulla.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = if (enabled) c.ink else c.graphite.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, c.line),
        modifier = Modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
    }
}

/**
 * Song progress: crown fill up to the position, the A–B loop as an indigo band,
 * and a thumb. Tap or drag to seek; [onSeek] gets a fraction 0..1 on release.
 */
@Composable
fun ProgressTrack(
    fraction: Float,
    loop: ClosedFloatingPointRange<Float>?,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Grulla.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics { stateDescription = "${(shown * 100).toInt()}%" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragging?.let(onSeek)
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        val y = size.height / 2
        val h = 6.dp.toPx()
        val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(c.track, topLeft = Offset(0f, y - h / 2), size = Size(size.width, h), cornerRadius = r)
        if (loop != null) {
            val lh = 12.dp.toPx()
            drawRoundRect(
                c.indigoHalo,
                topLeft = Offset(loop.start * size.width, y - lh / 2),
                size = Size((loop.endInclusive - loop.start) * size.width, lh),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
        drawRoundRect(c.crown, topLeft = Offset(0f, y - h / 2), size = Size(shown * size.width, h), cornerRadius = r)
        val thumb = Offset(shown * size.width, y)
        drawCircle(c.sheet, radius = 9.dp.toPx(), center = thumb)
        drawCircle(c.crown, radius = 7.dp.toPx(), center = thumb)
    }
}

/** A card on the sheet colour with a hairline border. */
@Composable
fun SheetCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = Grulla.colors
    val shape = RoundedCornerShape(16.dp)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = c.sheet, border = BorderStroke(1.dp, c.line), content = content)
    } else {
        Surface(modifier = modifier, shape = shape, color = c.sheet, border = BorderStroke(1.dp, c.line), content = content)
    }
}

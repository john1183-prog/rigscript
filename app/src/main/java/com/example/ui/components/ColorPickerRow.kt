package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A compact RGB colour picker: tappable swatch that expands into three 0-255
 * sliders. Operates on ARGB [Long] values (0xAARRGGBB) to match
 * [com.example.data.AppearanceSettings].
 *
 * Alpha is preserved from [colorArgb] and only exposed as a slider when
 * [showAlpha] is true — most figure/background colours in this app are fully
 * opaque, and exposing alpha for the MP4 export background would be misleading
 * since the NV12 encoder path has no alpha channel.
 */
@Composable
fun ColorPickerRow(
    label: String,
    colorArgb: Long,
    onColorChange: (Long) -> Unit,
    showAlpha: Boolean = false
) {
    val argb = colorArgb and 0xFFFFFFFFL
    val a = ((argb shr 24) and 0xFF).toInt()
    val r = ((argb shr 16) and 0xFF).toInt()
    val g = ((argb shr 8) and 0xFF).toInt()
    val b = (argb and 0xFF).toInt()

    var expanded by remember { mutableStateOf(false) }

    fun emit(newA: Int, newR: Int, newG: Int, newB: Int) {
        val packed = (newA.toLong() shl 24) or (newR.toLong() shl 16) or
            (newG.toLong() shl 8) or newB.toLong()
        onColorChange(packed and 0xFFFFFFFFL)
    }

    Column {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium)
            Text("${if (expanded) "▼" else "▶"}  ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a / 255f))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            ChannelSlider("R", r, Color(0xFFFF6B6B)) { emit(a, it, g, b) }
            ChannelSlider("G", g, Color(0xFF6BFF8E)) { emit(a, r, it, b) }
            ChannelSlider("B", b, Color(0xFF6B9CFF)) { emit(a, r, g, it) }
            if (showAlpha) ChannelSlider("A", a, Color.Gray) { emit(it, r, g, b) }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ChannelSlider(label: String, value: Int, tint: Color, onValue: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(20.dp), style = MaterialTheme.typography.labelSmall, color = tint,
            fontWeight = FontWeight.Bold)
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f)
        )
        Text(value.toString(), Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall)
    }
}

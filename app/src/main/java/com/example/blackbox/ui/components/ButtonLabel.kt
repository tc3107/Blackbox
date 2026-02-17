package com.example.blackbox.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

@Composable
fun ButtonLabel(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    minFontSize: TextUnit = 8.sp
) {
    val baseFontSize = if (style.fontSize.isSpecified) style.fontSize else 14.sp

    BoxWithConstraints(modifier = modifier) {
        var scaledFontSize by remember(text, baseFontSize, minFontSize, maxWidth) {
            mutableStateOf(baseFontSize)
        }

        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = style.copy(fontSize = scaledFontSize),
            onTextLayout = { result ->
                if (result.hasVisualOverflow && scaledFontSize > minFontSize) {
                    val next = (scaledFontSize.value - 0.5f).coerceAtLeast(minFontSize.value).sp
                    if (next < scaledFontSize) {
                        scaledFontSize = next
                    }
                }
            }
        )
    }
}

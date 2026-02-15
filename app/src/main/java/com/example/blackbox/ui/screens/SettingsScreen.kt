package com.example.blackbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.blackbox.data.settings.UiSettings
import com.example.blackbox.ui.theme.accentColorFromHex
import com.example.blackbox.ui.theme.normalizeAccentHex

@Composable
fun SettingsScreen(
    settings: UiSettings,
    onCustomAccentSaved: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputHex by rememberSaveable(settings.customAccentHex) {
        mutableStateOf(settings.customAccentHex.orEmpty())
    }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }

    val activeAccentColor = settings.customAccentHex?.let(::accentColorFromHex)
        ?: MaterialTheme.colorScheme.primary

    val modeDescription = if (settings.customAccentHex == null) {
        "Automatic mode: system Material 3 colors when available, otherwise terminal green."
    } else {
        "Custom mode: #${settings.customAccentHex}"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Color Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = modeDescription,
            style = MaterialTheme.typography.bodyMedium
        )
        ColorPreviewChip(color = activeAccentColor)
        OutlinedTextField(
            value = inputHex,
            onValueChange = {
                inputHex = it.trim().removePrefix("#")
                validationError = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "Custom accent hex") },
            placeholder = { Text(text = "00FF66", fontFamily = FontFamily.Monospace) },
            singleLine = true,
            prefix = { Text("#") },
            isError = validationError != null,
            supportingText = {
                Text(text = validationError ?: "Use a 6-digit RGB hex value.")
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            )
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    val normalized = normalizeAccentHex(inputHex)
                    if (normalized == null) {
                        validationError = "Invalid color value. Use 6 hex digits."
                    } else {
                        onCustomAccentSaved(normalized)
                        inputHex = normalized
                        validationError = null
                    }
                }
            ) {
                Text("Apply Override")
            }
            OutlinedButton(
                onClick = {
                    onCustomAccentSaved(null)
                    inputHex = ""
                    validationError = null
                }
            ) {
                Text("Use Automatic")
            }
        }
    }
}

@Composable
private fun ColorPreviewChip(color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(color = color, shape = CircleShape)
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape)
        )
        Text(
            text = "Active accent preview",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

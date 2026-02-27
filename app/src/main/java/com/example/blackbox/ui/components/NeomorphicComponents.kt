package com.example.blackbox.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.blackbox.ui.theme.neomorphicPalette
import com.example.blackbox.ui.theme.neomorphicShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog as M3AlertDialog
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.OutlinedCard as M3OutlinedCard
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.TextField as M3TextField
import androidx.compose.material3.TextButton as M3TextButton

private val NeoShadowPadding = 3.dp
private const val NeoTapHoldMs = 70L
private const val NeoSecondStageHapticDelayMs = 110L

enum class NeoButtonHapticMode {
    None,
    PressCycle,
    ToggleCycle
}

private fun android.view.View.emitNeoHaptic(code: Int, fallbackCode: Int = code) {
    if (!performHapticFeedback(code)) {
        performHapticFeedback(fallbackCode)
    }
}

private fun android.view.View.emitNeoLightHaptic() {
    emitNeoHaptic(
        code = HapticFeedbackConstants.KEYBOARD_TAP,
        fallbackCode = HapticFeedbackConstants.TEXT_HANDLE_MOVE
    )
}

private fun android.view.View.emitNeoConfirmHaptic() {
    emitNeoHaptic(code = HapticFeedbackConstants.LONG_PRESS, fallbackCode = HapticFeedbackConstants.LONG_PRESS)
}

@Composable
private fun defaultNeoButtonColors(): ButtonColors {
    val palette = neomorphicPalette()
    return ButtonDefaults.buttonColors(
        containerColor = palette.surface,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
        disabledContainerColor = palette.surface.copy(alpha = 0.55f),
        disabledContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NeoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    latched: Boolean = false,
    shape: Shape = RoundedCornerShape(16.dp),
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    hapticMode: NeoButtonHapticMode = NeoButtonHapticMode.PressCycle,
    toggleTargetState: Boolean? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed = source.collectIsPressedAsState().value
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var tapLatched by remember { mutableStateOf(false) }
    var hapticToken by remember { mutableStateOf(0L) }
    LaunchedEffect(tapLatched) {
        if (tapLatched) {
            delay(NeoTapHoldMs)
            tapLatched = false
        }
    }
    LaunchedEffect(source, hapticMode, enabled) {
        if (!enabled || hapticMode == NeoButtonHapticMode.None) return@LaunchedEffect
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> view.emitNeoLightHaptic()
                is PressInteraction.Release -> {
                    if (hapticMode == NeoButtonHapticMode.PressCycle) {
                        view.emitNeoConfirmHaptic()
                    }
                }
                else -> Unit
            }
        }
    }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        M3Button(
            onClick = {
                tapLatched = true
                val token = hapticToken + 1L
                hapticToken = token
                when (hapticMode) {
                    NeoButtonHapticMode.None -> Unit
                    NeoButtonHapticMode.PressCycle -> Unit
                    NeoButtonHapticMode.ToggleCycle -> {
                        scope.launch {
                            delay(NeoSecondStageHapticDelayMs)
                            if (hapticToken != token) return@launch
                            view.emitNeoConfirmHaptic()
                        }
                    }
                }
                onClick()
            },
            modifier = modifier
                .padding(NeoShadowPadding)
                .neomorphicShadow(
                    shape = shape,
                    enabled = enabled,
                    pressed = pressed || latched || tapLatched,
                    addBorder = false,
                    depth = 2.dp,
                    blurRadius = 4.dp
                ),
            enabled = enabled,
            shape = shape,
            colors = colors ?: defaultNeoButtonColors(),
            elevation = elevation ?: ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp,
                disabledElevation = 0.dp
            ),
            border = border,
            contentPadding = contentPadding,
            interactionSource = source,
            content = content
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NeoOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    latched: Boolean = false,
    shape: Shape = RoundedCornerShape(16.dp),
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    hapticMode: NeoButtonHapticMode = NeoButtonHapticMode.PressCycle,
    toggleTargetState: Boolean? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed = source.collectIsPressedAsState().value
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var tapLatched by remember { mutableStateOf(false) }
    var hapticToken by remember { mutableStateOf(0L) }
    LaunchedEffect(tapLatched) {
        if (tapLatched) {
            delay(NeoTapHoldMs)
            tapLatched = false
        }
    }
    LaunchedEffect(source, hapticMode, enabled) {
        if (!enabled || hapticMode == NeoButtonHapticMode.None) return@LaunchedEffect
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> view.emitNeoLightHaptic()
                is PressInteraction.Release -> {
                    if (hapticMode == NeoButtonHapticMode.PressCycle) {
                        view.emitNeoConfirmHaptic()
                    }
                }
                else -> Unit
            }
        }
    }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        M3OutlinedButton(
            onClick = {
                tapLatched = true
                val token = hapticToken + 1L
                hapticToken = token
                when (hapticMode) {
                    NeoButtonHapticMode.None -> Unit
                    NeoButtonHapticMode.PressCycle -> Unit
                    NeoButtonHapticMode.ToggleCycle -> {
                        scope.launch {
                            delay(NeoSecondStageHapticDelayMs)
                            if (hapticToken != token) return@launch
                            view.emitNeoConfirmHaptic()
                        }
                    }
                }
                onClick()
            },
            modifier = modifier
                .padding(NeoShadowPadding)
                .neomorphicShadow(
                    shape = shape,
                    enabled = enabled,
                    pressed = pressed || latched || tapLatched,
                    addBorder = false,
                    depth = 2.dp,
                    blurRadius = 4.dp
                ),
            enabled = enabled,
            shape = shape,
            colors = colors ?: defaultNeoButtonColors(),
            elevation = elevation ?: ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp,
                disabledElevation = 0.dp
            ),
            border = border,
            contentPadding = contentPadding,
            interactionSource = source,
            content = content
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NeoTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    latched: Boolean = false,
    shape: Shape = RoundedCornerShape(14.dp),
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    hapticMode: NeoButtonHapticMode = NeoButtonHapticMode.PressCycle,
    toggleTargetState: Boolean? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed = source.collectIsPressedAsState().value
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var tapLatched by remember { mutableStateOf(false) }
    var hapticToken by remember { mutableStateOf(0L) }
    LaunchedEffect(tapLatched) {
        if (tapLatched) {
            delay(NeoTapHoldMs)
            tapLatched = false
        }
    }
    LaunchedEffect(source, hapticMode, enabled) {
        if (!enabled || hapticMode == NeoButtonHapticMode.None) return@LaunchedEffect
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> view.emitNeoLightHaptic()
                is PressInteraction.Release -> {
                    if (hapticMode == NeoButtonHapticMode.PressCycle) {
                        view.emitNeoConfirmHaptic()
                    }
                }
                else -> Unit
            }
        }
    }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        M3TextButton(
            onClick = {
                tapLatched = true
                val token = hapticToken + 1L
                hapticToken = token
                when (hapticMode) {
                    NeoButtonHapticMode.None -> Unit
                    NeoButtonHapticMode.PressCycle -> Unit
                    NeoButtonHapticMode.ToggleCycle -> {
                        scope.launch {
                            delay(NeoSecondStageHapticDelayMs)
                            if (hapticToken != token) return@launch
                            view.emitNeoConfirmHaptic()
                        }
                    }
                }
                onClick()
            },
            modifier = modifier
                .padding(NeoShadowPadding)
                .neomorphicShadow(
                    shape = shape,
                    enabled = enabled,
                    pressed = pressed || latched || tapLatched,
                    addBorder = false,
                    depth = 2.dp,
                    blurRadius = 3.dp
                ),
            enabled = enabled,
            shape = shape,
            colors = colors ?: defaultNeoButtonColors(),
            contentPadding = contentPadding,
            interactionSource = source,
            content = content
        )
    }
}

@Composable
fun NeoCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    colors: CardColors? = null,
    elevation: CardElevation? = null,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = neomorphicPalette()
    M3Card(
        modifier = modifier
            .padding(NeoShadowPadding)
            .neomorphicShadow(
                shape = shape,
                addBorder = false,
                depth = 2.dp,
                blurRadius = 4.dp
            ),
        shape = shape,
        colors = colors ?: CardDefaults.cardColors(
            containerColor = palette.surface
        ),
        elevation = elevation ?: CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = border,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeoOutlinedCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    colors: CardColors? = null,
    elevation: CardElevation? = null,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = neomorphicPalette()
    M3OutlinedCard(
        modifier = modifier
            .padding(NeoShadowPadding)
            .neomorphicShadow(
                shape = shape,
                addBorder = false,
                depth = 2.dp,
                blurRadius = 4.dp
            ),
        shape = shape,
        colors = colors ?: CardDefaults.outlinedCardColors(
            containerColor = palette.surface
        ),
        elevation = elevation ?: CardDefaults.outlinedCardElevation(defaultElevation = 0.dp),
        border = border ?: BorderStroke(0.dp, androidx.compose.ui.graphics.Color.Transparent),
        content = content
    )
}

@Composable
fun NeoSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbContent: (@Composable () -> Unit)? = null,
    colors: SwitchColors? = null
) {
    val palette = neomorphicPalette()
    M3Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier
            .padding(NeoShadowPadding)
            .neomorphicShadow(
                shape = RoundedCornerShape(18.dp),
                enabled = enabled,
                addBorder = false,
                depth = 2.dp,
                blurRadius = 4.dp
            ),
        enabled = enabled,
        thumbContent = thumbContent,
        colors = colors ?: SwitchDefaults.colors(
            checkedThumbColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            checkedTrackColor = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
            checkedBorderColor = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            uncheckedThumbColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
            uncheckedTrackColor = palette.surfaceVariant,
            uncheckedBorderColor = palette.stroke
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeoOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = androidx.compose.material3.LocalTextStyle.current,
    label: (@Composable (() -> Unit))? = null,
    placeholder: (@Composable (() -> Unit))? = null,
    leadingIcon: (@Composable (() -> Unit))? = null,
    trailingIcon: (@Composable (() -> Unit))? = null,
    prefix: (@Composable (() -> Unit))? = null,
    suffix: (@Composable (() -> Unit))? = null,
    supportingText: (@Composable (() -> Unit))? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = RoundedCornerShape(16.dp),
    colors: TextFieldColors? = null
) {
    val palette = neomorphicPalette()
    M3TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .padding(NeoShadowPadding)
            .neomorphicShadow(
                shape = shape,
                enabled = enabled,
                addBorder = false,
                depth = 1.dp,
                blurRadius = 2.dp
            ),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        shape = shape,
        colors = colors ?: TextFieldDefaults.colors(
            focusedContainerColor = palette.surface,
            unfocusedContainerColor = palette.surface,
            disabledContainerColor = palette.surface.copy(alpha = 0.72f),
            errorContainerColor = palette.surface,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            errorIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
        )
    )
}

@Composable
fun NeoAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(22.dp),
    containerColor: androidx.compose.ui.graphics.Color = neomorphicPalette().surface,
    iconContentColor: androidx.compose.ui.graphics.Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: androidx.compose.ui.graphics.Color = AlertDialogDefaults.titleContentColor,
    textContentColor: androidx.compose.ui.graphics.Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties()
) {
    M3AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier
            .padding(NeoShadowPadding)
            .neomorphicShadow(shape = shape, addBorder = false),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties
    )
}

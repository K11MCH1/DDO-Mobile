package com.ddomobile.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A one-line text field where a double tap selects everything, so typing replaces it. This Compose's text fields
 * have no double-tap selection of their own, and a password's dots have no words to select anyway.
 */
@Composable
fun SelectAllTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        field,
        { field = it; if (it.text != value) onValueChange(it.text) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        modifier = modifier.pointerInput(Unit) {
            var lastUp = 0L
            awaitEachGesture {
                // Watched before the field sees it, and left unconsumed, so the field still focuses and places its cursor.
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                if (up.uptimeMillis - lastUp <= viewConfiguration.doubleTapTimeoutMillis) {
                    lastUp = 0L
                    // After the field has put its cursor where the second tap landed.
                    scope.launch {
                        delay(50)
                        field = field.copy(selection = TextRange(0, field.text.length))
                    }
                } else {
                    lastUp = up.uptimeMillis
                }
            }
        },
    )
}

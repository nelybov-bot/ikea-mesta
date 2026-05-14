package ru.ikea.cellmapper.scanner

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.ikea.cellmapper.logic.BarcodeLogic

/**
 * Невидимое поле с постоянным фокусом: внешний сканер (HID) печатает сюда
 * без необходимости открывать видимое поле ввода.
 */
@Composable
fun HiddenScannerInput(
    enabled: Boolean,
    onBarcodeScanned: (String) -> Unit,
    refocusKey: Any = Unit
) {
    val focusRequester = remember { FocusRequester() }
    var buffer by remember { mutableStateOf("") }

    fun flush() {
        val digits = BarcodeLogic.extractDigits(buffer)
        if (digits != null) {
            onBarcodeScanned(digits)
        }
        buffer = ""
    }

    LaunchedEffect(refocusKey, enabled) {
        if (enabled) {
            delay(100)
            runCatching { focusRequester.requestFocus() }
        }
    }

    LaunchedEffect(enabled) {
        while (enabled) {
            delay(600)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Box(modifier = Modifier.size(1.dp)) {
        BasicTextField(
            value = buffer,
            onValueChange = { newValue ->
                if (!enabled) return@BasicTextField

                buffer = BarcodeLogic.cleanBuffer(newValue)

                val wrapped = Regex("=(\\d{8,})=").find(buffer)
                if (wrapped != null) {
                    onBarcodeScanned(wrapped.groupValues[1])
                    buffer = ""
                }
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (!enabled) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Tab)
                    ) {
                        flush()
                        true
                    } else {
                        false
                    }
                },
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { flush() })
        )
    }
}

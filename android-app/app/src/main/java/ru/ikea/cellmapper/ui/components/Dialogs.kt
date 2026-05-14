package ru.ikea.cellmapper.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.ikea.cellmapper.viewmodel.ScanDialogState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanDialog(
    state: ScanDialogState,
    onDismiss: () -> Unit,
    onSaveNew: (cell: Int, qty: Int) -> Unit,
    onSaveDuplicate: (addQty: Int, keepOldCell: Boolean, newCell: Int) -> Unit
) {
    when (state) {
        is ScanDialogState.NewItem -> NewItemDialog(
            barcode = state.barcode,
            article = state.article,
            scanAndPlace = state.scanAndPlace,
            onDismiss = onDismiss,
            onSave = onSaveNew
        )

        is ScanDialogState.Duplicate -> DuplicateDialog(
            barcode = state.barcode,
            article = state.article,
            existingCell = state.existing.cell,
            existingQty = state.existing.qty,
            scanAndPlace = state.scanAndPlace,
            onDismiss = onDismiss,
            onSave = onSaveDuplicate
        )
    }
}

@Composable
private fun NewItemDialog(
    barcode: String,
    article: String,
    scanAndPlace: Boolean,
    onDismiss: () -> Unit,
    onSave: (cell: Int, qty: Int) -> Unit
) {
    var cellText by remember { mutableStateOf("1") }
    var qtyText by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (scanAndPlace) "Скан + место + кол-во" else "Запись места") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ШК: $barcode")
                Text("Артикул: $article")
                OutlinedTextField(
                    value = cellText,
                    onValueChange = { cellText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Место / ячейка") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (scanAndPlace) {
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Количество") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val cell = cellText.toIntOrNull() ?: return@Button
                val qty = if (scanAndPlace) qtyText.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
                onSave(cell, qty)
            }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun DuplicateDialog(
    barcode: String,
    article: String,
    existingCell: Int,
    existingQty: Int,
    scanAndPlace: Boolean,
    onDismiss: () -> Unit,
    onSave: (addQty: Int, keepOldCell: Boolean, newCell: Int) -> Unit
) {
    var addQtyText by remember { mutableStateOf("1") }
    var newCellText by remember { mutableStateOf(existingCell.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Товар уже сканировали") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ШК: $barcode")
                Text("Артикул: $article")
                Text("Сейчас: место $existingCell" + if (scanAndPlace) ", остаток $existingQty" else "")
                if (scanAndPlace) {
                    OutlinedTextField(
                        value = addQtyText,
                        onValueChange = { addQtyText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Добавить к остатку") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = newCellText,
                    onValueChange = { newCellText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Новое место (если переложить)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val addQty = if (scanAndPlace) addQtyText.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
                        onSave(addQty, true, existingCell)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (scanAndPlace) "Оставить старое место (+к остатку)"
                        else "Оставить старое место"
                    )
                }
                OutlinedButton(
                    onClick = {
                        val newCell = newCellText.toIntOrNull() ?: return@OutlinedButton
                        val addQty = if (scanAndPlace) addQtyText.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
                        onSave(addQty, false, newCell)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (scanAndPlace) "Переложить в новое место (+к остатку)"
                        else "Переложить в новое место"
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditItemDialog(
    barcode: String,
    article: String,
    initialCell: Int,
    initialQty: Int,
    scanAndPlace: Boolean,
    onDismiss: () -> Unit,
    onSave: (cell: Int, qty: Int) -> Unit,
    onDelete: () -> Unit
) {
    var cellText by remember { mutableStateOf(initialCell.toString()) }
    var qtyText by remember { mutableStateOf(initialQty.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактирование") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ШК: $barcode")
                Text("Артикул: $article")
                OutlinedTextField(
                    value = cellText,
                    onValueChange = { cellText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Место / ячейка") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (scanAndPlace) {
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Остаток") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val cell = cellText.toIntOrNull() ?: return@Button
                    val qty = if (scanAndPlace) qtyText.toIntOrNull()?.coerceAtLeast(0) ?: 0 else initialQty
                    onSave(cell, qty)
                }) { Text("Сохранить") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete) { Text("Удалить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )
}

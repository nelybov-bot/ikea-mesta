package ru.ikea.cellmapper.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.ikea.cellmapper.data.ItemEntity
import ru.ikea.cellmapper.ui.components.EditItemDialog
import ru.ikea.cellmapper.ui.components.ScanDialog
import ru.ikea.cellmapper.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit
) {
    val items by viewModel.items.collectAsState()
    val scanDialog by viewModel.scanDialog.collectAsState()
    val editDialog by viewModel.editDialog.collectAsState()
    val status by viewModel.status.collectAsState()
    val fileName by viewModel.fileName.collectAsState()
    val scanAndPlace by viewModel.scanAndPlaceEnabled.collectAsState()
    val refocusTick by viewModel.refocusTick.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ИКЕЯ — Места") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = fileName?.let { "Файл: $it" } ?: "Файл не выбран — откройте в настройках",
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Text(
                text = if (scanAndPlace) "Режим: скан + место + кол-во" else "Режим: только место",
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = status,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Сканируйте ШК внешним сканером — поле ввода открывать не нужно",
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.barcode }) { item ->
                    ItemRow(
                        item = item,
                        showQty = scanAndPlace,
                        onClick = { viewModel.openEdit(item) }
                    )
                }
            }
        }
    }

    scanDialog?.let { dialog ->
        ScanDialog(
            state = dialog,
            onDismiss = viewModel::dismissScanDialog,
            onSaveNew = { cell, qty ->
                when (dialog) {
                    is ru.ikea.cellmapper.viewmodel.ScanDialogState.NewItem ->
                        viewModel.saveNewItem(dialog.barcode, dialog.article, cell, qty, dialog.scanAndPlace)
                    else -> Unit
                }
            },
            onSaveDuplicate = { addQty, keepOldCell, newCell ->
                when (dialog) {
                    is ru.ikea.cellmapper.viewmodel.ScanDialogState.Duplicate ->
                        viewModel.saveDuplicate(
                            dialog.barcode,
                            addQty,
                            keepOldCell,
                            newCell,
                            dialog.scanAndPlace
                        )
                    else -> Unit
                }
            }
        )
    }

    editDialog?.let { dialog ->
        EditItemDialog(
            barcode = dialog.item.barcode,
            article = dialog.item.article,
            initialCell = dialog.item.cell,
            initialQty = dialog.item.qty,
            scanAndPlace = scanAndPlace,
            onDismiss = viewModel::dismissEditDialog,
            onSave = { cell, qty -> viewModel.saveEdit(dialog.item.barcode, cell, qty) },
            onDelete = { viewModel.deleteItem(dialog.item.barcode) }
        )
    }

    ru.ikea.cellmapper.scanner.HiddenScannerInput(
        enabled = viewModel.scannerEnabled,
        onBarcodeScanned = viewModel::onBarcodeScanned,
        refocusKey = refocusTick
    )
}

@Composable
private fun ItemRow(item: ItemEntity, showQty: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Место: ${item.cell}", fontWeight = FontWeight.Bold)
                Text("ШК: ${item.barcode}")
                Text("Артикул: ${item.article}")
            }
            if (showQty) {
                Text("× ${item.qty}", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val scanAndPlace by viewModel.scanAndPlaceEnabled.collectAsState()
    val fileName by viewModel.fileName.collectAsState()
    val context = LocalContext.current

    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "file.xlsx"
            viewModel.onFileSelected(uri, name)
        }
    }

    val createLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "cells_map.xlsx"
            viewModel.createNewFile(uri, name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Сканирование + запись мест", fontWeight = FontWeight.Bold)
                    Text("Вкл.: место и кол-во в одном окне. Выкл.: только место.")
                }
                Switch(
                    checked = scanAndPlace,
                    onCheckedChange = viewModel::setScanAndPlace
                )
            }

            Text("Текущий файл: ${fileName ?: "не выбран"}")

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel")) }
            ) {
                Text("Открыть Excel-файл…", modifier = Modifier.padding(16.dp))
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { createLauncher.launch("cells_map.xlsx") }
            ) {
                Text("Создать новый Excel…", modifier = Modifier.padding(16.dp))
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.manualSave() }
            ) {
                Text("Сохранить в текущий файл сейчас", modifier = Modifier.padding(16.dp))
            }
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            return cursor.getString(index)
        }
    }
    return null
}

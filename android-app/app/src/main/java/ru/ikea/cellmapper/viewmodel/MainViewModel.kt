package ru.ikea.cellmapper.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.ikea.cellmapper.data.AppDatabase
import ru.ikea.cellmapper.data.ItemEntity
import ru.ikea.cellmapper.data.ItemRepository
import ru.ikea.cellmapper.data.SettingsRepository
import ru.ikea.cellmapper.logic.BarcodeLogic

sealed class ScanDialogState {
    data class NewItem(
        val barcode: String,
        val article: String,
        val scanAndPlace: Boolean
    ) : ScanDialogState()

    data class Duplicate(
        val barcode: String,
        val article: String,
        val existing: ItemEntity,
        val scanAndPlace: Boolean
    ) : ScanDialogState()
}

data class EditDialogState(
    val item: ItemEntity
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsRepository(application)
    private val repository = ItemRepository(
        application,
        AppDatabase.get(application).itemDao(),
        settings
    )

    val items = repository.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val scanAndPlaceEnabled = settings.scanAndPlaceEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val fileName = settings.fileName.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _scanDialog = MutableStateFlow<ScanDialogState?>(null)
    val scanDialog: StateFlow<ScanDialogState?> = _scanDialog.asStateFlow()

    private val _editDialog = MutableStateFlow<EditDialogState?>(null)
    val editDialog: StateFlow<EditDialogState?> = _editDialog.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _refocusTick = MutableStateFlow(0)
    val refocusTick: StateFlow<Int> = _refocusTick.asStateFlow()

    val scannerEnabled: Boolean
        get() = _scanDialog.value == null && _editDialog.value == null

    init {
        viewModelScope.launch {
            settings.fileUri.first()?.let { uriString ->
                runCatching {
                    repository.loadFromFile(Uri.parse(uriString))
                }.onSuccess {
                    _status.value = "Данные загружены из файла"
                }
            }
        }
    }

    fun onBarcodeScanned(raw: String) {
        viewModelScope.launch {
            val digits = BarcodeLogic.extractDigits(raw) ?: run {
                _status.value = "Не удалось распознать ШК"
                return@launch
            }
            val article = try {
                BarcodeLogic.barcodeToArticle(digits)
            } catch (e: Exception) {
                _status.value = e.message ?: "Ошибка артикула"
                return@launch
            }

            val existing = repository.findByBarcode(digits)
            val scanAndPlace = scanAndPlaceEnabled.value

            _scanDialog.value = if (existing != null) {
                ScanDialogState.Duplicate(digits, article, existing, scanAndPlace)
            } else {
                ScanDialogState.NewItem(digits, article, scanAndPlace)
            }
        }
    }

    fun dismissScanDialog() {
        _scanDialog.value = null
        bumpRefocus()
    }

    fun saveNewItem(barcode: String, article: String, cell: Int, qty: Int, scanAndPlace: Boolean) {
        viewModelScope.launch {
            repository.saveNew(
                barcode = barcode,
                article = article,
                cell = cell,
                qty = if (scanAndPlace) qty else 0
            )
            _status.value = if (scanAndPlace) {
                "Сохранено: ячейка $cell | $barcode | +$qty"
            } else {
                "Сохранено место: ячейка $cell | $barcode"
            }
            dismissScanDialog()
        }
    }

    fun saveDuplicate(
        barcode: String,
        addQty: Int,
        keepOldCell: Boolean,
        newCell: Int,
        scanAndPlace: Boolean
    ) {
        viewModelScope.launch {
            if (scanAndPlace) {
                repository.addQuantity(barcode, addQty, keepOldCell, newCell)
                _status.value = "Добавлено к остатку: +$addQty"
            } else {
                if (keepOldCell) {
                    _status.value = "Место не изменено"
                } else {
                    repository.updatePlace(barcode, repository.findByBarcode(barcode)?.article ?: "", newCell)
                    _status.value = "Место обновлено: ячейка $newCell"
                }
            }
            dismissScanDialog()
        }
    }

    fun openEdit(item: ItemEntity) {
        _editDialog.value = EditDialogState(item)
    }

    fun dismissEditDialog() {
        _editDialog.value = null
        bumpRefocus()
    }

    fun saveEdit(barcode: String, cell: Int, qty: Int) {
        viewModelScope.launch {
            repository.editItem(barcode, cell, qty)
            _status.value = "Остаток обновлён: ячейка $cell | $qty шт."
            dismissEditDialog()
        }
    }

    fun deleteItem(barcode: String) {
        viewModelScope.launch {
            repository.deleteItem(barcode)
            _status.value = "Удалено"
            dismissEditDialog()
        }
    }

    fun setScanAndPlace(enabled: Boolean) {
        viewModelScope.launch { settings.setScanAndPlaceEnabled(enabled) }
    }

    fun onFileSelected(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            settings.setFile(uri.toString(), displayName)
            repository.loadFromFile(uri)
            _status.value = "Файл загружен: $displayName"
        }
    }

    fun createNewFile(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            settings.setFile(uri.toString(), displayName)
            repository.persistToCurrentFile()
            _status.value = "Создан файл: $displayName"
        }
    }

    fun manualSave() {
        viewModelScope.launch {
            val result = repository.persistToCurrentFile()
            _status.value = result.fold(
                onSuccess = { "Сохранено в текущий файл" },
                onFailure = { it.message ?: "Ошибка сохранения" }
            )
        }
    }

    private fun bumpRefocus() {
        _refocusTick.value++
    }
}

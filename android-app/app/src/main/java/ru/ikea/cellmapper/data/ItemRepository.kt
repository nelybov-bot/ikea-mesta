package ru.ikea.cellmapper.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.ikea.cellmapper.export.XlsxStorage

class ItemRepository(
    private val context: Context,
    private val dao: ItemDao,
    private val settings: SettingsRepository
) {
    val items: Flow<List<ItemEntity>> = dao.observeAll()

    suspend fun loadFromFile(uri: Uri) = withContext(Dispatchers.IO) {
        val loaded = XlsxStorage.read(context, uri)
        dao.deleteAll()
        loaded.forEach { dao.upsert(it) }
    }

    suspend fun persistToCurrentFile(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val uriString = settings.fileUri.first() ?: error("Файл не выбран")
            val uri = Uri.parse(uriString)
            val all = dao.getAll()
            XlsxStorage.write(context, uri, all)
        }
    }

    suspend fun findByBarcode(barcode: String): ItemEntity? = dao.findByBarcode(barcode)

    suspend fun saveNew(barcode: String, article: String, cell: Int, qty: Int) {
        dao.upsert(
            ItemEntity(
                barcode = barcode,
                article = article,
                cell = cell,
                qty = qty,
                updatedAt = System.currentTimeMillis()
            )
        )
        persistToCurrentFileSilently()
    }

    suspend fun updatePlace(barcode: String, article: String, newCell: Int) {
        val existing = dao.findByBarcode(barcode) ?: return
        dao.upsert(existing.copy(cell = newCell, article = article, updatedAt = System.currentTimeMillis()))
        persistToCurrentFileSilently()
    }

    suspend fun addQuantity(barcode: String, addQty: Int, keepOldCell: Boolean, newCell: Int) {
        val existing = dao.findByBarcode(barcode) ?: return
        val cell = if (keepOldCell) existing.cell else newCell
        dao.upsert(
            existing.copy(
                cell = cell,
                qty = existing.qty + addQty,
                updatedAt = System.currentTimeMillis()
            )
        )
        persistToCurrentFileSilently()
    }

    suspend fun editItem(barcode: String, cell: Int, qty: Int) {
        val existing = dao.findByBarcode(barcode) ?: return
        dao.upsert(existing.copy(cell = cell, qty = qty, updatedAt = System.currentTimeMillis()))
        persistToCurrentFileSilently()
    }

    suspend fun deleteItem(barcode: String) {
        dao.deleteByBarcode(barcode)
        persistToCurrentFileSilently()
    }

    private suspend fun persistToCurrentFileSilently() {
        runCatching { persistToCurrentFile() }
    }
}

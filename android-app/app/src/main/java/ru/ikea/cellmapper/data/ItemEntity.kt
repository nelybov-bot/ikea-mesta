package ru.ikea.cellmapper.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val barcode: String,
    val article: String,
    val cell: Int,
    val qty: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY cell ASC, barcode ASC")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): ItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ItemEntity)

    @Update
    suspend fun update(item: ItemEntity)

    @Query("DELETE FROM items WHERE barcode = :barcode")
    suspend fun deleteByBarcode(barcode: String)

    @Query("DELETE FROM items")
    suspend fun deleteAll()

    @Query("SELECT * FROM items ORDER BY cell ASC, barcode ASC")
    suspend fun getAll(): List<ItemEntity>
}

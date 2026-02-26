package com.resqmesh.app.data

import android.content.Context
import androidx.room.*
import com.resqmesh.app.DeliveryStatus
import com.resqmesh.app.SosType
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sos_messages")
data class SosMessageEntity(
    @PrimaryKey val id: String,
    val type: SosType,
    val message: String,
    val timestamp: Long,
    var status: DeliveryStatus,
    val latitude: Double? = null,
    val longitude: Double? = null
)

class Converters {
    @TypeConverter
    fun fromSosType(value: SosType): String = value.name

    @TypeConverter
    fun toSosType(value: String): SosType = SosType.valueOf(value)

    @TypeConverter
    fun fromDeliveryStatus(value: DeliveryStatus): String = value.name

    @TypeConverter
    fun toDeliveryStatus(value: String): DeliveryStatus = DeliveryStatus.valueOf(value)
}

@Dao
interface SosDao {
    @Query("SELECT * FROM sos_messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<SosMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: SosMessageEntity)

    @Query("UPDATE sos_messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: DeliveryStatus)

    @Query("SELECT * FROM sos_messages WHERE status = 'PENDING'")
    suspend fun getPendingMessages(): List<SosMessageEntity>

    // NEW: The Garbage Collector SQL Query
    @Query("DELETE FROM sos_messages WHERE timestamp < :thresholdTime")
    suspend fun deleteMessagesOlderThan(thresholdTime: Long)
}

@Database(entities = [SosMessageEntity::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class ResQMeshDatabase : RoomDatabase() {
    abstract fun sosDao(): SosDao

    companion object {
        @Volatile private var INSTANCE: ResQMeshDatabase? = null

        fun getDatabase(context: Context): ResQMeshDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ResQMeshDatabase::class.java,
                    "resqmesh_offline_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class SosRepository(private val dao: SosDao) {
    val allMessages: Flow<List<SosMessageEntity>> = dao.getAllMessages()

    suspend fun saveMessage(message: SosMessageEntity) {
        dao.insertMessage(message)
    }

    suspend fun updateMessageStatus(id: String, status: DeliveryStatus) {
        dao.updateStatus(id, status)
    }

    suspend fun getPendingMessages(): List<SosMessageEntity> {
        return dao.getPendingMessages()
    }

    // NEW: Expose the delete function to the ViewModel
    suspend fun deleteOldMessages(thresholdTime: Long) {
        dao.deleteMessagesOlderThan(thresholdTime)
    }
}
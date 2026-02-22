package com.resqmesh.app.data

import android.content.Context
import androidx.room.*
import com.resqmesh.app.DeliveryStatus
import com.resqmesh.app.SosType
import kotlinx.coroutines.flow.Flow

// 1. The Entity (How the table looks)
@Entity(tableName = "sos_messages")
data class SosMessageEntity(
    @PrimaryKey val id: String,
    val type: SosType,
    val message: String,
    val timestamp: Long,
    val status: DeliveryStatus,
    val latitude: Double? = null,
    val longitude: Double? = null
)

// 2. Type Converters (Now with explicit return types for KSP)
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
// 3. The DAO (Data Access Object - Your SQL Queries)
@Dao
interface SosDao {
    @Query("SELECT * FROM sos_messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<SosMessageEntity>> // Flow automatically updates the UI!

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: SosMessageEntity)
}

// 4. The Database Setup
@Database(entities = [SosMessageEntity::class], version = 1, exportSchema = false)
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
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// 5. The Repository (Single Source of Truth)
class SosRepository(private val dao: SosDao) {
    val allMessages: Flow<List<SosMessageEntity>> = dao.getAllMessages()

    suspend fun saveMessage(message: SosMessageEntity) {
        dao.insertMessage(message)
    }
}
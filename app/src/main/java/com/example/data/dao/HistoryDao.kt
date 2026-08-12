package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.ActivityItem
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM recent_activities ORDER BY timestamp DESC LIMIT 30")
    fun getAllActivities(): Flow<List<ActivityItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivity(item: ActivityItem)

    @Query("DELETE FROM recent_activities WHERE id = :id")
    suspend fun deleteActivityById(id: Long)

    @Query("DELETE FROM recent_activities")
    suspend fun clearAll()
}

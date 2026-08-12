package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ActivityType {
    EXTRACT,
    CREATE
}

enum class OperationStatus {
    SUCCESS,
    FAILED,
    CANCELLED
}

@Entity(tableName = "recent_activities")
data class ActivityItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val fileFormat: String, // ZIP or RAR
    val fileSizeFormatted: String,
    val type: ActivityType,
    val status: OperationStatus,
    val itemsCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

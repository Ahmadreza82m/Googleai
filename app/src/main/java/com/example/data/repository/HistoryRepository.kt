package com.example.data.repository

import com.example.data.dao.HistoryDao
import com.example.data.model.ActivityItem
import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val historyDao: HistoryDao) {
    val allActivities: Flow<List<ActivityItem>> = historyDao.getAllActivities()

    suspend fun addActivity(item: ActivityItem) {
        historyDao.insertActivity(item)
    }

    suspend fun deleteActivity(id: Long) {
        historyDao.deleteActivityById(id)
    }

    suspend fun clearHistory() {
        historyDao.clearAll()
    }
}

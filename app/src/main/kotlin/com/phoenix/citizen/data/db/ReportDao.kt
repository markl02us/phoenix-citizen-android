package com.phoenix.citizen.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: ReportEntity): Long

    @Update
    suspend fun update(report: ReportEntity)

    @Query("SELECT * FROM reports ORDER BY id DESC")
    fun observeAll(): Flow<List<ReportEntity>>

    @Query("SELECT * FROM reports WHERE status IN (:statuses) ORDER BY id ASC")
    suspend fun byStatus(statuses: List<String>): List<ReportEntity>

    @Query("SELECT * FROM reports WHERE id = :id")
    suspend fun byId(id: Long): ReportEntity?

    @Query("SELECT * FROM reports WHERE status = 'SYNCED' AND corroboratedAt IS NULL")
    suspend fun pendingCorroboration(): List<ReportEntity>

    @Query("DELETE FROM reports WHERE id = :id")
    suspend fun delete(id: Long)
}

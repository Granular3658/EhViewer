package com.ehviewer.core.database.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "suggestions")
data class Search(
    @ColumnInfo(name = "date")
    val date: Long,
    @ColumnInfo(name = "query")
    val query: String,
    @PrimaryKey
    @ColumnInfo(name = "_id")
    val id: Int? = null,
)

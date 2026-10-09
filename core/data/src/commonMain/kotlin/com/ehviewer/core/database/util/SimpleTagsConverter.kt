package com.ehviewer.core.database.util

import androidx.room3.ColumnTypeConverter

class SimpleTagsConverter {
    @ColumnTypeConverter
    fun fromString(value: String) = value.split(", ")

    @ColumnTypeConverter
    fun toString(value: List<String>) = value.joinToString()
}

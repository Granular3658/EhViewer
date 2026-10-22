package com.ehviewer.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal expect fun getDataStore(name: String?): DataStore<Preferences>

private val collectorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

abstract class DataStorePreferences(private val dataStore: DataStore<Preferences>) {
    constructor(name: String?) : this(getDataStore(name))

    /**
     * The last snapshot the store emitted, kept so that reading a preference does
     * not have to start a coroutine.
     *
     * [PrefDelegate.value] is read from composition and from draw, on the main
     * thread, where a `runBlocking` round trip to the store is not affordable: a
     * card that reads a preference every time it is composed turned that into an
     * ANR, because the composition happens inside the frame's measure pass. A
     * read that a frame is itself waiting for cannot complete either.
     */
    @Volatile
    private var cached: Snapshot? = null

    init {
        collectorScope.launch {
            dataStore.data.collect { cached = Snapshot(it) }
        }
    }

    /** The snapshot to read from without blocking, if one has arrived yet. */
    internal val current: Snapshot?
        get() = cached

    val data: Flow<Snapshot>
        get() = dataStore.data.map { Snapshot(it) }

    suspend fun snapshot(): Snapshot = data.first()

    suspend fun withMutableSnapshot(block: (MutableSnapshot) -> Unit) {
        dataStore.edit {
            block(MutableSnapshot(it))
        }
    }

    protected fun boolPref(
        key: String,
        defaultValue: Boolean,
    ) = PrefDelegate(this, booleanPreferencesKey(name = key), defaultValue = defaultValue)

    protected fun intPref(
        key: String,
        defaultValue: Int,
    ) = PrefDelegate(this, intPreferencesKey(name = key), defaultValue = defaultValue)

    protected fun floatPref(
        key: String,
        defaultValue: Float,
    ) = PrefDelegate(this, floatPreferencesKey(name = key), defaultValue = defaultValue)

    protected fun doublePref(
        key: String,
        defaultValue: Double,
    ) = PrefDelegate(this, doublePreferencesKey(name = key), defaultValue = defaultValue)

    protected fun longPref(
        key: String,
        defaultValue: Long,
    ) = PrefDelegate(this, longPreferencesKey(name = key), defaultValue = defaultValue)

    protected fun stringPref(
        key: String,
        defaultValue: String,
    ) = PrefDelegate(this, stringPreferencesKey(name = key), defaultValue = defaultValue)

    protected fun stringOrNullPref(
        key: String,
    ) = PrefDelegate(this, stringPreferencesKey(name = key), null)

    protected fun stringSetPref(
        key: String,
        defaultValue: Set<String> = emptySet(),
    ) = PrefDelegate(this, stringSetPreferencesKey(name = key), defaultValue = defaultValue)

    protected fun stringSetOrNullPref(
        key: String,
    ) = PrefDelegate(this, stringSetPreferencesKey(name = key), null)

    protected fun byteArrayPref(
        key: String,
        defaultValue: ByteArray,
    ) = PrefDelegate(this, byteArrayPreferencesKey(name = key), defaultValue = defaultValue)
}

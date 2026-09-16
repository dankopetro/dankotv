package tv.danko.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("dankotv")

/** Ajustes persistentes. Espejo de DEFAULT_SETTINGS en dankotv/config.py. */
class SettingsStore(private val ds: DataStore<Preferences>) {

    companion object {
        fun create(context: Context) = SettingsStore(context.dataStore)
        const val DEF_SKIN = "Naranja Oscuro"
        const val DEF_FONT = "Ubuntu"
        const val DEF_GROUP = "Todas"
        const val DEF_CACHE_SECS = 45
    }

    private object K {
        val SKIN = stringPreferencesKey("skin")
        val FONT = stringPreferencesKey("font_family")
        val LAST_LIST = stringPreferencesKey("last_list")
        val LAST_GROUP = stringPreferencesKey("last_group")
        val CACHE_SECS = intPreferencesKey("cache_secs")
    }

    val skin: Flow<String> = ds.data.map { it[K.SKIN] ?: DEF_SKIN }
    val fontFamily: Flow<String> = ds.data.map { it[K.FONT] ?: DEF_FONT }
    val lastList: Flow<String> = ds.data.map { it[K.LAST_LIST] ?: "" }
    val lastGroup: Flow<String> = ds.data.map { it[K.LAST_GROUP] ?: DEF_GROUP }
    val cacheSecs: Flow<Int> = ds.data.map { it[K.CACHE_SECS] ?: DEF_CACHE_SECS }

    suspend fun setSkin(v: String) {
        ds.edit { it[K.SKIN] = v }
    }
    suspend fun setFontFamily(v: String) {
        ds.edit { it[K.FONT] = v }
    }
    suspend fun setLastList(v: String) {
        ds.edit { it[K.LAST_LIST] = v }
    }
    suspend fun setLastGroup(v: String) {
        ds.edit { it[K.LAST_GROUP] = v }
    }
    suspend fun setCacheSecs(v: Int) {
        ds.edit { it[K.CACHE_SECS] = v }
    }
}

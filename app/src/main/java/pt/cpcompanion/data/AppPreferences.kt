package pt.cpcompanion.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.cpPreferences by preferencesDataStore(name = "cp_preferences")

/** Small non-sensitive settings stored transactionally outside SharedPreferences. */
class AppPreferences(context: Context) {
    private val dataStore = context.applicationContext.cpPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    suspend fun readString(key: String): String? = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .first()[stringPreferencesKey(key)]

    fun writeString(key: String, value: String?) {
        scope.launch {
            dataStore.edit { preferences ->
                val preferenceKey = stringPreferencesKey(key)
                if (value == null) preferences.remove(preferenceKey) else preferences[preferenceKey] = value
            }
        }
    }

    suspend fun writeStringNow(key: String, value: String?) {
        dataStore.edit { preferences ->
            val preferenceKey = stringPreferencesKey(key)
            if (value == null) preferences.remove(preferenceKey) else preferences[preferenceKey] = value
        }
    }
}


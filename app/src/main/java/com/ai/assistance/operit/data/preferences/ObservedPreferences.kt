package com.ai.assistance.operit.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.core.awareness.SoftwareAwareness
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadOnlyProperty

/** Observe committed writes, not conflated flows or first reads mistaken for new settings. */
internal fun observedPreferencesDataStore(
    name: String,
    produceMigrations: (Context) -> List<DataMigration<Preferences>> = { emptyList() },
): ReadOnlyProperty<Context, DataStore<Preferences>> {
    val delegate = preferencesDataStore(name = name, produceMigrations = produceMigrations)
    var wrapped: DataStore<Preferences>? = null
    val lock = Any()
    return ReadOnlyProperty { context, property ->
        synchronized(lock) {
            wrapped ?: run {
                val actual = delegate.getValue(context, property)
                val app = context.applicationContext
                object : DataStore<Preferences> {
                    override val data = actual.data
                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        var previous: Preferences? = null
                        val actor = AssistantScope.callerId()
                        val result = actual.updateData { old ->
                            previous = old
                            transform(old)
                        }
                        previous?.let { old ->
                            SoftwareAwareness.preferencesChanged(app, name,
                                old.asMap().mapKeys { it.key.name }, result.asMap().mapKeys { it.key.name }, actor)
                        }
                        return result
                    }
                }.also { wrapped = it }
            }
        }
    }
}

private val observedShared = ConcurrentHashMap<String, SharedPreferences>()

fun Context.observedSharedPreferences(name: String, mode: Int): SharedPreferences =
    observedShared.getOrPut("${applicationContext.filesDir}:$name") {
        AuditedSharedPreferences(applicationContext, name, getSharedPreferences(name, mode))
    }

private class AuditedSharedPreferences(
    private val app: Context, private val name: String, private val actual: SharedPreferences,
) : SharedPreferences by actual {
    private val lock = Any()
    override fun edit(): SharedPreferences.Editor = Editor(actual.edit())

    private inner class Editor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor by editor {
        override fun putString(key: String?, value: String?) = apply { editor.putString(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { editor.putStringSet(key, values) }
        override fun putInt(key: String?, value: Int) = apply { editor.putInt(key, value) }
        override fun putLong(key: String?, value: Long) = apply { editor.putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = apply { editor.putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = apply { editor.putBoolean(key, value) }
        override fun remove(key: String?) = apply { editor.remove(key) }
        override fun clear() = apply { editor.clear() }
        override fun commit(): Boolean = synchronized(lock) {
            val before = actual.all
            val success = editor.commit()
            if (success) SoftwareAwareness.preferencesChanged(app, name, before, actual.all, AssistantScope.callerId())
            success
        }
        override fun apply() = synchronized(lock) {
            val before = actual.all
            editor.apply()
            SoftwareAwareness.preferencesChanged(app, name, before, actual.all, AssistantScope.callerId())
        }
    }
}

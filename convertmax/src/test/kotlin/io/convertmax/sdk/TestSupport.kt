package io.convertmax.sdk

import android.content.Context
import android.content.SharedPreferences
import org.mockito.Mockito

/** In-memory SharedPreferences so the SDK can run on the JVM without an Android runtime. */
internal class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (key != null) data[key] = value; return this }
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { if (key != null) data[key] = values; return this }
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) data[key] = value; return this }
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) data[key] = value; return this }
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) data[key] = value; return this }
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) data[key] = value; return this }
    override fun remove(key: String?): SharedPreferences.Editor { data.remove(key); return this }
    override fun clear(): SharedPreferences.Editor { data.clear(); return this }
    override fun commit(): Boolean = true
    override fun apply() {}
}

internal class FakePrefs : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = data
    override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (data[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(data)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

/** A Context whose preferences are in memory and whose applicationContext is not an Application, so no SQLite is used. */
internal fun fakeContext(): Context {
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.applicationContext).thenReturn(context)
    Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(FakePrefs())
    return context
}

package io.convertmax.sdk

import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.mockito.Mockito

class ConvertmaxTest {
    private class FakeEditor(private val data: MutableMap<String, Any?>) : android.content.SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { if (key != null) data[key] = value; return this }
        override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { if (key != null) data[key] = values; return this }
        override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { if (key != null) data[key] = value; return this }
        override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { if (key != null) data[key] = value; return this }
        override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { if (key != null) data[key] = value; return this }
        override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { if (key != null) data[key] = value; return this }
        override fun remove(key: String?): android.content.SharedPreferences.Editor { data.remove(key); return this }
        override fun clear(): android.content.SharedPreferences.Editor { data.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() {}
    }

    private class FakePrefs : android.content.SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private fun mockContext(): android.content.Context {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(FakePrefs())
        return context
    }

    @Test fun consentAndIdentityBoundaries() {
        val sdk = Convertmax.create(mockContext(), Configuration("public", "demo"))
        assertNull(sdk.track("opened")); sdk.setConsent(Consent.GRANTED); sdk.identify("account-a")
        assertEquals("account-a", sdk.track("signup")?.userId); sdk.reset(); assertNull(sdk.track("after-reset")?.userId)
    }

    @Test fun mobileV1AckDropsAcceptedAndNonRetryableRejected() {
        val body = """{"contract":"mobile-v1","results":[
            {"messageId":"AAA","status":"accepted"},
            {"messageId":"BBB","status":"rejected","code":"empty_name","retryable":false},
            {"messageId":"CCC","status":"rejected","retryable":true}
        ]}"""
        assertEquals(setOf("aaa", "bbb"), droppableMessageIds(202, body))
        assertNull(droppableMessageIds(200, "{}"))
    }

    @Test fun gzipPayloadHasGzipMagic() {
        val bytes = gzipBytes("convertmax".toByteArray())
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)
    }
}

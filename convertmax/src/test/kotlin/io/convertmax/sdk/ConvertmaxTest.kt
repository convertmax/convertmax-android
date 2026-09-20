package io.convertmax.sdk

import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.mockito.Mockito

class ConvertmaxTest {
    @Test fun consentAndIdentityBoundaries() {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        val sdk = Convertmax.create(context, Configuration("public", "demo"))
        assertNull(sdk.track("opened")); sdk.setConsent(Consent.GRANTED); sdk.identify("account-a")
        assertEquals("account-a", sdk.track("signup")?.userId); sdk.reset(); assertNull(sdk.track("after-reset")?.userId)
    }
}

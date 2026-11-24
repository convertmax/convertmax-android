package io.convertmax.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConvertmaxTest {
    @Test fun consentAndIdentityBoundaries() {
        val sdk = Convertmax.create(fakeContext(), Configuration("public", "demo"))
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

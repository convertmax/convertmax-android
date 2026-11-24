package io.convertmax.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class EventWritePlanTest {
    private fun event(id: String) = Event(messageId = id, type = EventType.TRACK, event = "e", anonymousId = "anon",
        userId = null, properties = emptyMap(), appId = "demo", environment = "production")

    @Test fun deletesOnlyStoredIdsThatLeftTheQueueAndInsertsOnlyNewEvents() {
        val plan = planEventWrite(persistedIds = setOf("a", "b"), events = listOf(event("b"), event("c")))

        assertEquals(setOf("a"), plan.toDelete)
        assertEquals(listOf("c"), plan.toInsert.map { it.messageId })
    }

    @Test fun unchangedQueueWritesNothing() {
        val plan = planEventWrite(persistedIds = setOf("a"), events = listOf(event("a")))

        assertEquals(emptySet(), plan.toDelete)
        assertEquals(emptyList(), plan.toInsert)
    }
}

package io.convertmax.sdk

import android.content.Context
import java.util.UUID

enum class Consent { UNKNOWN, GRANTED, DENIED }
enum class EventType { TRACK, IDENTIFY, SCREEN }

data class Configuration(val writeKey: String, val appId: String, val environment: String = "production",
                         val endpoint: String = "https://event.convertmax.io/v1/batch")
data class Event(val messageId: String = UUID.randomUUID().toString(), val type: EventType,
                 val event: String? = null, val name: String? = null, val timestamp: Long = System.currentTimeMillis(),
                 val anonymousId: String, val userId: String?, val properties: Map<String, String>, val appId: String,
                 val environment: String)

/** Enqueue-first API. SQLite, WorkManager and retry adapters are deliberately separate implementation slices. */
class Convertmax private constructor(context: Context, private val configuration: Configuration) {
    private var consent = Consent.UNKNOWN
    private var anonymousId = UUID.randomUUID().toString()
    private var userId: String? = null

    fun setConsent(value: Consent) { consent = value; if (value != Consent.GRANTED) { anonymousId = UUID.randomUUID().toString(); userId = null } }
    fun identify(id: String) { if (consent == Consent.GRANTED && id.isNotEmpty()) userId = id }
    fun reset() { userId = null; anonymousId = UUID.randomUUID().toString() }
    fun track(name: String, properties: Map<String, String> = emptyMap()): Event? =
        if (consent == Consent.GRANTED && name.isNotEmpty()) event(EventType.TRACK, name, null, properties) else null
    fun screen(name: String, properties: Map<String, String> = emptyMap()): Event? =
        if (consent == Consent.GRANTED && name.isNotEmpty()) event(EventType.SCREEN, null, name, properties) else null

    private fun event(type: EventType, event: String?, name: String?, properties: Map<String, String>) =
        Event(type = type, event = event, name = name, anonymousId = anonymousId, userId = userId,
              properties = properties, appId = configuration.appId, environment = configuration.environment)

    companion object { fun create(context: Context, configuration: Configuration) = Convertmax(context.applicationContext, configuration) }
}

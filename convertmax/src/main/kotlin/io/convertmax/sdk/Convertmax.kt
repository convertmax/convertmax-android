package io.convertmax.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

enum class Consent { UNKNOWN, GRANTED, DENIED }
enum class EventType { TRACK, IDENTIFY, SCREEN }

data class Configuration(val writeKey: String, val appId: String, val environment: String = "production",
                         val endpoint: String = "https://event.convertmax.io/v1/batch")
data class Event(val messageId: String = UUID.randomUUID().toString(), val type: EventType,
                 val event: String? = null, val name: String? = null, val timestamp: Long = System.currentTimeMillis(),
                 val anonymousId: String, val userId: String?, val properties: Map<String, String>, val appId: String,
                 val environment: String)
data class DeliveryResult(val delivered: Int, val retained: Int, val attempts: Int)
data class Diagnostics(val queued: Int, val dropped: Int)

internal fun droppableMessageIds(httpStatus: Int, body: String): Set<String>? {
    if (httpStatus !in 200..299) return null
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
    if (json.optString("contract").lowercase() != "mobile-v1") return null
    val results = json.optJSONArray("results") ?: return emptySet()
    val drop = mutableSetOf<String>()
    for (i in 0 until results.length()) {
        val item = results.optJSONObject(i) ?: continue
        val id = item.optString("messageId").lowercase()
        val status = item.optString("status")
        val retryable = item.optBoolean("retryable", false)
        if (status == "accepted" || (status == "rejected" && !retryable)) drop.add(id)
    }
    return drop
}

/** Enqueue-first API. SQLite, WorkManager and retry adapters are deliberately separate implementation slices. */
class Convertmax private constructor(context: Context, private val configuration: Configuration) {
    private var consent = Consent.UNKNOWN
    private var anonymousId = UUID.randomUUID().toString()
    private var userId: String? = null
    private val queue = mutableListOf<Event>()
    private var dropped = 0

    fun setConsent(value: Consent) { consent = value; if (value != Consent.GRANTED) { anonymousId = UUID.randomUUID().toString(); userId = null } }
    fun identify(id: String) { if (consent == Consent.GRANTED && id.isNotEmpty()) userId = id }
    fun reset() { userId = null; anonymousId = UUID.randomUUID().toString() }
    fun track(name: String, properties: Map<String, String> = emptyMap()): Event? =
        if (consent == Consent.GRANTED && name.isNotEmpty()) enqueue(event(EventType.TRACK, name, null, properties)) else null
    fun screen(name: String, properties: Map<String, String> = emptyMap()): Event? =
        if (consent == Consent.GRANTED && name.isNotEmpty()) enqueue(event(EventType.SCREEN, null, name, properties)) else null
    fun diagnostics(): Diagnostics = Diagnostics(queue.size, dropped)
    fun flush(): List<Event> { val copy = queue.toList(); queue.clear(); return copy }

    fun flushToNetwork(maxAttempts: Int = 3): DeliveryResult {
        var attempts = 0
        var delivered = 0
        while (attempts < maxOf(1, maxAttempts) && queue.isNotEmpty()) {
            attempts += 1
            val batch = queue.take(50)
            try {
                val connection = (URL(configuration.endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${configuration.writeKey}")
                    setRequestProperty("X-Convertmax-Contract", "mobile-v1")
                    setRequestProperty("Content-Type", "application/json")
                }
                connection.outputStream.use { it.write(encodeBatch(batch).toByteArray()) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                val drop = droppableMessageIds(code, text) ?: throw IllegalStateException("unknown ack")
                val before = queue.size
                queue.removeAll { drop.contains(it.messageId.lowercase()) }
                delivered += (before - queue.size).coerceAtLeast(0)
            } catch (_: Exception) {
                if (attempts < maxOf(1, maxAttempts)) Thread.sleep(250L * attempts)
            }
        }
        return DeliveryResult(delivered, queue.size, attempts)
    }

    private fun enqueue(event: Event): Event? {
        if (queue.size >= 1000) { dropped += 1; return null }
        queue.add(event); return event
    }

    private fun encodeBatch(batch: List<Event>): String {
        val events = JSONArray()
        for (item in batch) {
            events.put(JSONObject().put("messageId", item.messageId).put("type", item.type.name.lowercase())
                .put("event", item.event ?: JSONObject.NULL).put("name", item.name ?: JSONObject.NULL)
                .put("timestamp", item.timestamp).put("anonymousId", item.anonymousId)
                .put("userId", item.userId ?: JSONObject.NULL)
                .put("properties", JSONObject(item.properties as Map<*, *>))
                .put("context", JSONObject().put("appId", item.appId).put("environment", item.environment)))
        }
        return JSONObject().put("events", events).toString()
    }

    private fun event(type: EventType, event: String?, name: String?, properties: Map<String, String>) =
        Event(type = type, event = event, name = name, anonymousId = anonymousId, userId = userId,
              properties = properties, appId = configuration.appId, environment = configuration.environment)

    companion object { fun create(context: Context, configuration: Configuration) = Convertmax(context.applicationContext, configuration) }
}

package io.convertmax.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.GZIPOutputStream

enum class Consent { UNKNOWN, GRANTED, DENIED }
enum class EventType { TRACK, IDENTIFY, SCREEN }

data class Configuration(val writeKey: String, val appId: String, val environment: String = "production",
                         val endpoint: String = "https://event.convertmax.io/v1/batch",
                         val sessionTimeoutMs: Long = 30 * 60 * 1000L,
                         val flushIntervalMs: Long = 30_000L)
data class Event(val messageId: String = UUID.randomUUID().toString(), val type: EventType,
                 val event: String? = null, val name: String? = null, val timestamp: Long = System.currentTimeMillis(),
                 val anonymousId: String, val userId: String?, val properties: Map<String, String>, val appId: String,
                 val environment: String, val sessionId: String = "")
data class DeliveryResult(val delivered: Int, val retained: Int, val attempts: Int, val rejected: Int = 0)
data class Diagnostics(val queued: Int, val dropped: Int)

internal fun gzipBytes(bytes: ByteArray): ByteArray {
    val sink = ByteArrayOutputStream()
    GZIPOutputStream(sink).use { it.write(bytes) }
    return sink.toByteArray()
}

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

/** Which rows a save must delete and insert, given the rows currently stored. */
internal data class EventWritePlan(val toDelete: Set<String>, val toInsert: List<Event>)

/**
 * Diffs the queue against the stored message IDs. Stored rows that are not decodable are never in
 * [persistedIds], so they are never deleted.
 */
internal fun planEventWrite(persistedIds: Set<String>, events: List<Event>): EventWritePlan {
    val current = events.mapTo(mutableSetOf()) { it.messageId }
    return EventWritePlan(
        toDelete = persistedIds - current,
        toInsert = events.filter { it.messageId !in persistedIds },
    )
}

private class EventDb(context: Context) : SQLiteOpenHelper(context, "convertmax-events.sqlite", null, 1) {
    /** Message IDs of decodable rows currently stored. */
    private var persistedIds: Set<String> = emptySet()
    /** Next created_at sequence value, so insertion order survives relaunch. */
    private var nextSequence = 0L

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (message_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun load(): MutableList<Event> {
        val out = mutableListOf<Event>()
        val ids = mutableSetOf<String>()
        var maxSequence = -1L
        readableDatabase.rawQuery("SELECT message_id, payload, created_at FROM events ORDER BY created_at ASC", null).use { cursor ->
            while (cursor.moveToNext()) {
                maxSequence = maxOf(maxSequence, cursor.getLong(2))
                // Undecodable rows are left out of persistedIds, so save() never deletes them.
                decode(cursor.getString(1))?.let { out.add(it); ids.add(cursor.getString(0)) }
            }
        }
        persistedIds = ids
        nextSequence = maxSequence + 1
        return out
    }
    /** Writes only the difference from what is stored. Event payloads are immutable, so rows are never rewritten. */
    fun save(events: List<Event>) {
        val plan = planEventWrite(persistedIds, events)
        var sequence = nextSequence
        writableDatabase.beginTransaction()
        try {
            plan.toDelete.forEach { writableDatabase.execSQL("DELETE FROM events WHERE message_id = ?", arrayOf(it)) }
            plan.toInsert.forEach { event ->
                writableDatabase.execSQL(
                    "INSERT INTO events(message_id, payload, created_at) VALUES (?, ?, ?)",
                    arrayOf(event.messageId, encode(event), sequence++)
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        // Only reached when the transaction committed; a failed write leaves the bookkeeping unchanged.
        persistedIds = events.mapTo(mutableSetOf()) { it.messageId }
        nextSequence = sequence
    }
    private fun encode(event: Event): String = JSONObject()
        .put("messageId", event.messageId).put("type", event.type.name)
        .put("event", event.event ?: JSONObject.NULL).put("name", event.name ?: JSONObject.NULL)
        .put("timestamp", event.timestamp).put("anonymousId", event.anonymousId)
        .put("userId", event.userId ?: JSONObject.NULL)
        .put("properties", JSONObject(event.properties as Map<*, *>))
        .put("appId", event.appId).put("environment", event.environment).put("sessionId", event.sessionId).toString()
    private fun decode(payload: String): Event? = runCatching {
        val json = JSONObject(payload)
        Event(
            messageId = json.getString("messageId"),
            type = EventType.valueOf(json.getString("type")),
            event = json.optString("event").takeIf { json.has("event") && !json.isNull("event") },
            name = json.optString("name").takeIf { json.has("name") && !json.isNull("name") },
            timestamp = json.getLong("timestamp"),
            anonymousId = json.getString("anonymousId"),
            userId = json.optString("userId").takeIf { json.has("userId") && !json.isNull("userId") },
            properties = json.optJSONObject("properties")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } ?: emptyMap(),
            appId = json.getString("appId"),
            environment = json.getString("environment"),
            sessionId = json.optString("sessionId", ""),
        )
    }.getOrNull()
}

/** Enqueue-first API. */
class Convertmax private constructor(context: Context, private val configuration: Configuration) {
    companion object { const val VERSION = "0.2.0"; fun create(context: Context, configuration: Configuration) = Convertmax(context.applicationContext, configuration) }
    private var consent = Consent.UNKNOWN
    private var anonymousId = UUID.randomUUID().toString()
    private var userId: String? = null
    private var sessionId = UUID.randomUUID().toString()
    private var lastActivity = 0L
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("convertmax-${configuration.appId}-${configuration.environment}", Context.MODE_PRIVATE)
    private val db = (context.applicationContext as? Application)?.let { EventDb(it) }
    private val queue: MutableList<Event> = db?.load() ?: mutableListOf()
    private val flushLock = Any()
    private var dropped = 0
    private var startedActivities = 0

    init {
        anonymousId = prefs.getString("anonymousId", anonymousId) ?: anonymousId
        userId = prefs.getString("userId", null)
        sessionId = prefs.getString("sessionId", sessionId) ?: sessionId
        lastActivity = prefs.getLong("lastActivity", 0L)
        consent = runCatching { Consent.valueOf(prefs.getString("consent", Consent.UNKNOWN.name)!!) }.getOrDefault(Consent.UNKNOWN)
        if (consent != Consent.GRANTED) { queue.clear(); persist() }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities += 1 }
            override fun onActivityStopped(activity: Activity) {
                startedActivities -= 1
                if (startedActivities <= 0) Thread { flushToNetwork() }.start()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    @Synchronized fun setConsent(value: Consent) { consent = value; if (value != Consent.GRANTED) { anonymousId = UUID.randomUUID().toString(); userId = null; sessionId = UUID.randomUUID().toString(); queue.clear() }; persist() }
    @Synchronized fun identify(id: String): Event? { if (consent != Consent.GRANTED || id.isBlank()) return null; userId = id; persist(); return enqueue(event(EventType.IDENTIFY, null, null, emptyMap())) }
    @Synchronized fun reset() { userId = null; anonymousId = UUID.randomUUID().toString(); sessionId = UUID.randomUUID().toString(); lastActivity = 0L; persist() }
    fun track(name: String, properties: Map<String, String> = emptyMap()): Event? =
        if (consent == Consent.GRANTED && name.isNotEmpty()) enqueue(event(EventType.TRACK, name, null, properties)) else null
    fun screen(name: String, properties: Map<String, String> = emptyMap()): Event? =
        if (consent == Consent.GRANTED && name.isNotEmpty()) enqueue(event(EventType.SCREEN, null, name, properties)) else null
    fun revenue(transactionReference: String, amount: String? = null, currency: String? = null): Event? {
        if (transactionReference.isBlank()) return null
        val values = buildMap { put("transactionReference", transactionReference); amount?.let { put("amount", it) }; currency?.let { put("currency", it) } }
        return track("purchase_observed", values)
    }
    fun handleDeepLink(uri: android.net.Uri): Map<String, String> = listOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "referrer").mapNotNull { key -> uri.getQueryParameter(key)?.takeIf { it.length <= 256 }?.let { key to it } }.toMap()
    @Synchronized fun diagnostics(): Diagnostics = Diagnostics(queue.size, dropped)
    @Synchronized fun flush(): List<Event> { val copy = queue.toList(); queue.clear(); persist(); return copy }

    /**
     * Sends queued events in batches of 50. Flushes are serialized by [flushLock]. The queue lock is
     * held only while reading or mutating the queue, never during the network call.
     */
    fun flushToNetwork(maxAttempts: Int = 3): DeliveryResult = synchronized(flushLock) {
        var attempts = 0
        var delivered = 0
        // Events the server answered as retryable stay queued, but are skipped for the rest of this
        // call so they cannot block the events behind them (head-of-line blocking).
        val answered = mutableSetOf<String>()
        while (attempts < maxOf(1, maxAttempts)) {
            val batch = synchronized(this) { queue.filter { it.messageId !in answered }.take(50) }
            if (batch.isEmpty()) break
            attempts += 1
            try {
                val connection = (URL(configuration.endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${configuration.writeKey}")
                    setRequestProperty("X-Convertmax-Contract", "mobile-v1")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Content-Encoding", "gzip")
                }
                connection.outputStream.use { it.write(gzipBytes(encodeBatch(batch).toByteArray())) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                val drop = droppableMessageIds(code, text) ?: throw IllegalStateException("unknown ack")
                synchronized(this) {
                    val before = queue.size
                    queue.removeAll { drop.contains(it.messageId.lowercase()) }
                    delivered += (before - queue.size).coerceAtLeast(0)
                    persist()
                }
                answered += batch.map { it.messageId }
            } catch (_: Exception) {
                if (attempts < maxOf(1, maxAttempts)) Thread.sleep(250L * attempts)
            }
        }
        synchronized(this) { DeliveryResult(delivered, queue.size, attempts) }
    }

    @Synchronized private fun enqueue(event: Event): Event? {
        if (queue.size >= 1000) { dropped += 1; return null }
        queue.add(event); persist(); return event
    }

    private fun persist() { db?.save(queue); prefs.edit().putString("consent", consent.name).putString("anonymousId", anonymousId).putString("userId", userId).putString("sessionId", sessionId).putLong("lastActivity", lastActivity).apply() }

    private fun encodeBatch(batch: List<Event>): String {
        val events = JSONArray()
        for (item in batch) {
            events.put(JSONObject().put("messageId", item.messageId).put("type", item.type.name.lowercase())
                .put("event", item.event ?: JSONObject.NULL).put("name", item.name ?: JSONObject.NULL)
                .put("timestamp", item.timestamp).put("anonymousId", item.anonymousId)
                .put("userId", item.userId ?: JSONObject.NULL)
                .put("properties", JSONObject(item.properties as Map<*, *>))
                .put("context", JSONObject().put("appId", item.appId).put("environment", item.environment)
                    .put("sessionId", item.sessionId.ifEmpty { sessionId }).put("sdkName", "convertmax-android").put("sdkVersion", VERSION)
                    .put("platform", "android").put("osVersion", android.os.Build.VERSION.RELEASE)
                    .put("deviceModel", android.os.Build.MODEL)))
        }
        return JSONObject().put("events", events).toString()
    }

    /** Stamps the session at creation time, so an event keeps the session it happened in. */
    private fun event(type: EventType, event: String?, name: String?, properties: Map<String, String>): Event {
        val now = System.currentTimeMillis()
        if (now - lastActivity >= configuration.sessionTimeoutMs) sessionId = UUID.randomUUID().toString()
        lastActivity = now
        return Event(type = type, event = event, name = name, anonymousId = anonymousId, userId = userId,
              properties = properties, appId = configuration.appId, environment = configuration.environment,
              sessionId = sessionId)
    }
}

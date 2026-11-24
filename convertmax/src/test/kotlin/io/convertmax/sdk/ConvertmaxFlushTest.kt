package io.convertmax.sdk

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal local mobile-v1 ingest stub. The Android unit-test classpath has no `com.sun.net.httpserver`,
 * so this speaks HTTP/1.1 directly over a ServerSocket. It records every event it receives (after gunzip)
 * and answers `rejected` + `retryable` for events named in [retryEventNames], `accepted` for the rest.
 */
private class IngestStub(private val retryEventNames: Set<String> = emptySet()) : AutoCloseable {
    val received = mutableListOf<JSONObject>()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val worker = Thread {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            runCatching { socket.use { handle(it) } }
        }
    }.apply { isDaemon = true; start() }

    val endpoint: String get() = "http://127.0.0.1:${server.localPort}/v1/batch"

    private fun handle(socket: Socket) {
        val input = socket.getInputStream().buffered()
        readLine(input) ?: return
        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("content-length:")) contentLength = line.substringAfter(':').trim().toInt()
        }
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) return
            read += n
        }
        val batch = JSONObject(GZIPInputStream(ByteArrayInputStream(body)).readBytes().decodeToString())
        val events = batch.getJSONArray("events")
        val results = JSONArray()
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            synchronized(received) { received.add(event) }
            val retryable = event.optString("event") in retryEventNames
            results.put(JSONObject()
                .put("messageId", event.getString("messageId"))
                .put("status", if (retryable) "rejected" else "accepted")
                .put("retryable", retryable))
        }
        val reply = JSONObject().put("contract", "mobile-v1").put("results", results).toString().toByteArray()
        val head = "HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray()); write(reply); flush() }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (b == '\n'.code) return buffer.toString().trimEnd('\r')
            buffer.append(b.toChar())
        }
    }

    override fun close() { server.close(); worker.join(2_000) }
}

class ConvertmaxFlushTest {
    private var ingest: IngestStub? = null

    @AfterTest fun tearDown() { ingest?.close(); ingest = null }

    private fun sdk(stub: IngestStub, sessionTimeoutMs: Long = 30 * 60 * 1000L): Convertmax {
        ingest = stub
        val config = Configuration(writeKey = "public", appId = "demo", endpoint = stub.endpoint, sessionTimeoutMs = sessionTimeoutMs)
        return Convertmax.create(fakeContext(), config).also { it.setConsent(Consent.GRANTED) }
    }

    @Test fun flushDeliversEveryAcceptedEventAndEmptiesTheQueue() {
        val stub = IngestStub()
        val sdk = sdk(stub)
        repeat(3) { sdk.track("event-$it") }

        val result = sdk.flushToNetwork()

        assertEquals(3, result.delivered)
        assertEquals(0, result.retained)
        assertEquals(0, sdk.diagnostics().queued)
        assertEquals(3, stub.received.size)
    }

    @Test fun eachEventKeepsTheSessionItWasCreatedInEvenWhenFlushedLater() {
        val stub = IngestStub()
        // A zero timeout starts a new session for every event, so the two events belong to different sessions.
        val sdk = sdk(stub, sessionTimeoutMs = 0)
        val first = assertNotNull(sdk.track("first"))
        val second = assertNotNull(sdk.track("second"))
        assertNotEquals(first.sessionId, second.sessionId)

        sdk.flushToNetwork()

        val sessionByMessage = stub.received.associate { event ->
            event.getString("messageId") to event.getJSONObject("context").getString("sessionId")
        }
        assertEquals(first.sessionId, sessionByMessage[first.messageId])
        assertEquals(second.sessionId, sessionByMessage[second.messageId])
    }

    @Test fun retryableEventDoesNotBlockEventsQueuedBehindIt() {
        val stub = IngestStub(retryEventNames = setOf("retry-me"))
        val sdk = sdk(stub)
        sdk.track("retry-me")
        repeat(200) { sdk.track("ok-$it") }

        // Attempt 1 sends the first 50 (one retryable, 49 accepted); attempts 2 and 3 move on to new events.
        // The previous behavior resent the retryable event at the head of every batch and delivered less.
        val result = sdk.flushToNetwork(maxAttempts = 3)

        assertEquals(149, result.delivered)
        assertEquals(52, result.retained) // 201 queued - 149 delivered
        assertEquals(52, sdk.diagnostics().queued)
    }
}

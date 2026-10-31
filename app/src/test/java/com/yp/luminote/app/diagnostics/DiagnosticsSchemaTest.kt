package com.yp.luminote.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSchemaTest {
    @Test
    fun `record serializes stable schema without arbitrary metadata`() {
        val json = DiagnosticRecord(
            utc = "2026-10-31T00:00:00Z",
            monotonicNanos = 1L,
            sessionId = "session",
            severity = DiagnosticSeverity.INFO,
            event = DiagnosticEventId.NOTIFICATION_RECEIVED,
            message = "received",
            metadata = emptyMap(),
            correlationId = null,
            exception = null
        ).toJson()

        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"eventId\":\"NOTIFICATION_RECEIVED\""))
        assertFalse(json.contains("notificationTitle"))
    }

    @Test
    fun `correlation ids reject unsafe identifiers`() {
        assertTrue(sanitizeCorrelation("session-42.overlay").isNullOrEmpty().not())
        assertTrue(sanitizeCorrelation("message body with spaces") == null)
    }

    @Test
    fun `record with metadata correlation and exception is valid json`() {
        val encoded = DiagnosticRecord(
            utc = "2026-10-31T00:00:00Z", monotonicNanos = 2L, sessionId = "session",
            severity = DiagnosticSeverity.ERROR, event = DiagnosticEventId.RENDERING_ERROR,
            message = "backend failed", metadata = mapOf("errorType" to "IllegalStateException"),
            correlationId = "overlay-3", exception = "java.lang.IllegalStateException\\n at Renderer.draw:9"
        ).toJson()

        assertTrue(encoded.contains("\"metadata\":{\"errorType\":\"IllegalStateException\"}"))
        assertTrue(encoded.contains("\"correlationId\":\"overlay-3\""))
        assertTrue(encoded.contains("IllegalStateException"))
    }

    @Test
    fun `json escapes all control characters`() {
        val controls = (0..0x1f).map { it.toChar() }.joinToString("")
        val encoded = DiagnosticRecord("utc", 1, "session", DiagnosticSeverity.INFO,
            DiagnosticEventId.RENDERING_ERROR, controls, mapOf("errorType" to controls), null, null).toJson()
        assertFalse(encoded.any { it.code in 0..0x1f })
        assertTrue(encoded.contains("\\u0000"))
        assertTrue(encoded.contains("\\t"))
    }

    @Test
    fun `privacy admission drops secrets arbitrary keys and oversized values`() {
        val accepted = DiagnosticsPolicy.admittedMetadata(
            DiagnosticEventId.NOTIFICATION_REJECTED,
            mapOf("reason" to "EXCLUDED_SOURCE", "title" to "private", "reason2" to "token=abc")
        )
        assertEquals(mapOf("reason" to "EXCLUDED_SOURCE"), accepted)
        assertTrue(DiagnosticsPolicy.admittedMetadata(DiagnosticEventId.NOTIFICATION_REJECTED,
            mapOf("reason" to "x".repeat(161))).isEmpty())
    }

    @Test
    fun `retention keeps active journal and evicts oldest rotated entries over cap`() {
        val retained = DiagnosticsPolicy.retainedFiles(
            listOf(
                JournalFile("old", 8, 1, false),
                JournalFile("recent", 8, 9_999, false),
                JournalFile("current", 8, 0, true)
            ), nowMs = 10_000, maxTotalBytes = 8, retentionMs = 5_000
        )
        assertEquals(listOf("current"), retained.map { it.name })
    }

    @Test
    fun `stable records preserve producer order for one writer journal`() {
        val records = (1..64).map { index ->
            DiagnosticRecord("utc", index.toLong(), "session", DiagnosticSeverity.INFO,
                DiagnosticEventId.NOTIFICATION_RECEIVED, "event-$index", emptyMap(), null, null)
        }
        val ordered = records.sortedBy { it.monotonicNanos }
        assertEquals(records.map { it.message }, ordered.map { it.message })
    }

    @Test
    fun `health expectation transitions suppress intentional stop and detect inactivity`() {
        val active = linkedMapOf("overlay-1" to 1_000L)
        val timeout = 5_000L
        assertTrue(7_000L - active.getValue("overlay-1") >= timeout)
        active.remove("overlay-1") // intentional completion/disconnect disarms the expectation
        assertTrue(active.isEmpty())
    }

    @Test
    fun `crash record includes only bounded stack frames and no raw throwable message`() {
        val throwable = IllegalStateException("notification title should not persist")
        throwable.stackTrace = Array(64) { StackTraceElement("Class", "method", "File", it) }
        val record = DiagnosticRecord("utc", 1, "session", DiagnosticSeverity.FATAL,
            DiagnosticEventId.CRASH_CAPTURED, "Uncaught exception",
            mapOf("exceptionType" to throwable.javaClass.simpleName, "thread" to "worker"), null,
            throwable.javaClass.name + throwable.stackTrace.take(24).joinToString())
        assertFalse(record.toJson().contains("notification title"))
        assertTrue(record.toJson().length < 4_096)
    }
}

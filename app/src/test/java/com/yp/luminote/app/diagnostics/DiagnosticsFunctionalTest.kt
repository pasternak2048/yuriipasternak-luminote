package com.yp.luminote.app.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsFunctionalTest {
    private fun temp() = Files.createTempDirectory("diagnostics-test").toFile()

    @Test fun `journal accepts concurrent producers and preserves every complete line`() {
        val root = temp(); val journal = DiagnosticsJournal(root, 1_000_000, 2_000_000)
        val pool = Executors.newFixedThreadPool(4); val start = CountDownLatch(1)
        val lines = Collections.synchronizedList(mutableListOf<String>())
        repeat(32) { index -> pool.execute { start.await(); val line = "event-$index"; assertTrue(journal.append(line)); lines += line } }
        start.countDown(); pool.shutdown(); while (!pool.isTerminated) Thread.yield()
        val actual = root.walkTopDown().filter(File::isFile).flatMap { it.readLines().asSequence() }.toSet()
        assertEquals(lines.toSet(), actual)
    }

    @Test fun `journal preserves ordered writes from its single serialized writer`() {
        val root = temp(); val journal = DiagnosticsJournal(root, 1_000_000, 2_000_000)
        val expected = (0 until 16).map { "ordered-$it" }
        expected.forEach { assertTrue(journal.append(it)) }
        assertEquals(expected, File(root, "events-current.jsonl").readLines())
    }

    @Test fun `rotation cap snapshot and clear use real files without corrupting snapshot`() {
        val root = temp(); val journal = DiagnosticsJournal(root, 12, 45)
        repeat(12) { assertTrue(journal.append("entry-$it")) }
        assertTrue(root.listFiles().orEmpty().sumOf { it.length() } <= 45)
        val merged = File(temp(), "snapshot/events.jsonl")
        val bytes = journal.writeMergedSnapshot(merged, 45)
        journal.clear()
        assertTrue(bytes in 1..45); assertTrue(merged.exists() && merged.readText().isNotEmpty())
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".jsonl") })
    }

    @Test fun `journal reports storage failure`() {
        val file = File(temp(), "not-a-directory").apply { writeText("x") }
        assertFalse(DiagnosticsJournal(file, 10, 10).append("event"))
    }

    @Test fun `archive contains parseable expected entries`() {
        val root = temp()
        val contents = mapOf(
            "manifest.json" to "{\"schemaVersion\":1}",
            "device.json" to "{\"sdkInt\":36}",
            "events.jsonl" to "{\"eventId\":\"APP_STARTED\"}\n",
            "crashes.jsonl" to "{\"eventId\":\"CRASH_CAPTURED\"}\n",
            "health.json" to "{\"storedBytes\":0}"
        )
        val entries = contents.mapValues { (name, body) -> File(root, name).apply { writeText(body) } }
        val zip = File(root, "diagnostics.zip")
        zip.outputStream().use { output ->
            DiagnosticsArchive.writeDiagnostics(
                output, entries.getValue("manifest.json").readText(), entries.getValue("device.json").readText(),
                entries.getValue("events.jsonl"), entries.getValue("crashes.jsonl"), entries.getValue("health.json").readText()
            )
        }
        val extracted = mutableMapOf<String, String>()
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                extracted[entry.name] = input.readBytes().decodeToString()
            }
        }
        assertEquals(contents, extracted)
        assertTrue(
            extracted.getValue("events.jsonl")
                .lineSequence()
                .filter(String::isNotBlank)
                .all { it.startsWith("{") && it.endsWith("}") }
        )
        assertTrue(extracted.getValue("manifest.json").contains("\"schemaVersion\":1"))
        assertTrue(extracted.getValue("device.json").contains("\"sdkInt\":36"))
        assertTrue(extracted.getValue("health.json").contains("\"storedBytes\":0"))
    }

    @Test fun `crash persistence failure and reentrancy still delegate`() {
        var persisted = 0; var flushed = 0; var delegated = 0
        lateinit var handler: CrashDelegator
        var nested = false
        handler = CrashDelegator(
            persist = { _, _ -> persisted++; error("disk") },
            flush = { flushed++ },
            delegate = { thread, error ->
                delegated++
                if (!nested) { nested = true; handler.handle(thread, error) }
            }
        )
        handler.handle(Thread.currentThread(), IllegalStateException("failure"))
        assertEquals(1, persisted); assertEquals(1, flushed); assertEquals(2, delegated)
    }

    @Test fun `sessions differ and health detects stall but not intentional stop`() {
        val firstProcess = DiagnosticsSessionOwner { "session-a" }
        val restartedProcess = DiagnosticsSessionOwner { "session-b" }
        assertEquals("session-a", firstProcess.sessionId)
        assertFalse(firstProcess.sessionId == restartedProcess.sessionId)
        val health = HealthExpectations(); health.arm("session-overlay-1", 100)
        assertEquals(setOf("session-overlay-1"), health.stalled(5_100, 5_000))
        health.disarm("session-overlay-1")
        assertTrue(health.stalled(10_000, 5_000).isEmpty())
    }
}

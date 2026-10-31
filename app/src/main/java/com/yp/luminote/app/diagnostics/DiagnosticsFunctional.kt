package com.yp.luminote.app.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** File-backed journal primitive kept separate from Android so its failure and rotation rules are testable. */
internal class DiagnosticsJournal(
    private val root: File,
    private val maxFileBytes: Long,
    private val maxTotalBytes: Long
) {
    private val active = File(root, "events-current.jsonl")

    @Synchronized fun append(line: String): Boolean = runCatching {
        root.mkdirs()
        active.appendText(line + "\n")
        if (active.length() > maxFileBytes) {
            val rotated = File(root, "events-${System.nanoTime()}.jsonl")
            check(active.renameTo(rotated))
        }
        trim()
    }.isSuccess

    @Synchronized fun snapshot(destination: File): List<File> {
        destination.mkdirs()
        return root.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }
            .sortedBy { it.name }.map { source ->
                File(destination, source.name).also { target -> source.copyTo(target, overwrite = true) }
            }
    }

    @Synchronized fun clear() { root.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.forEach(File::delete) }

    @Synchronized fun files(): List<File> =
        root.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.sortedBy { it.name }

    @Synchronized fun enforceRetention(retentionMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - retentionMs
        files().filter { it != active && it.lastModified() < cutoff }.forEach(File::delete)
        trim()
    }

    /**
     * The production export snapshot. Keeping aggregation here makes the same
     * cap and serialization path executable in JVM tests.
     */
    @Synchronized fun writeMergedSnapshot(destination: File, maxBytes: Long): Long {
        destination.parentFile?.mkdirs()
        var copied = 0L
        FileOutputStream(destination, false).use { output ->
            files().forEach { source ->
                if (copied >= maxBytes) return@forEach
                source.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (copied < maxBytes) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - copied).toInt())
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                    }
                }
            }
        }
        return copied
    }

    private fun trim() {
        var bytes = root.listFiles().orEmpty().sumOf { it.length() }
        root.listFiles().orEmpty().filter { it != active }.sortedBy { it.lastModified() }.forEach { file ->
            if (bytes > maxTotalBytes) { bytes -= file.length(); file.delete() }
        }
    }
}

internal object DiagnosticsArchive {
    fun write(destination: File, entries: Map<String, File>) {
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            entries.forEach { (name, source) ->
                zip.putNextEntry(ZipEntry(name))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun copyToZip(zip: ZipOutputStream, name: String, source: File, beforeBuffer: () -> Unit = {}) {
        zip.putNextEntry(ZipEntry(name))
        try {
            source.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    beforeBuffer()
                    val count = input.read(buffer)
                    if (count < 0) break
                    zip.write(buffer, 0, count)
                }
            }
        } finally {
            zip.closeEntry()
        }
    }

    /** Single archive contract used by Android export and JVM integrity tests. */
    fun writeDiagnostics(
        output: OutputStream,
        manifest: String,
        device: String,
        events: File,
        crashes: File?,
        health: String,
        beforeBuffer: () -> Unit = {}
    ) {
        ZipOutputStream(output).use { zip ->
            writeText(zip, "manifest.json", manifest)
            writeText(zip, "device.json", device)
            copyToZip(zip, "events.jsonl", events, beforeBuffer)
            crashes?.takeIf(File::exists)?.let { copyToZip(zip, "crashes.jsonl", it, beforeBuffer) }
            writeText(zip, "health.json", health)
        }
    }

    private fun writeText(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray())
        zip.closeEntry()
    }
}

internal class HealthExpectations {
    private val expected = linkedMapOf<String, Long>()
    fun arm(id: String, now: Long) { expected[id] = now }
    fun activity(id: String, now: Long) { if (id in expected) expected[id] = now }
    fun disarm(id: String) { expected.remove(id) }
    fun stalled(now: Long, timeout: Long): Set<String> = expected.filterValues { now - it >= timeout }.keys
}

/** Shared by the application handler and JVM tests: persistence never prevents delegation. */
internal class CrashDelegator(
    private val persist: (Thread, Throwable) -> Unit,
    private val flush: () -> Unit,
    private val delegate: (Thread, Throwable) -> Unit
) {
    private val handling = java.util.concurrent.atomic.AtomicBoolean(false)

    fun handle(thread: Thread, error: Throwable) {
        if (!handling.compareAndSet(false, true)) {
            delegate(thread, error)
            return
        }
        try {
            runCatching { persist(thread, error) }
            runCatching(flush)
            /* Keep the guard held while delegating: a delegate may synchronously
             * re-enter the handler, but must not attempt a second persistence pass. */
            delegate(thread, error)
        } finally {
            handling.set(false)
        }
    }
}

/** Repository session construction is centralized so a fresh process always gets a fresh session. */
internal object DiagnosticsSession {
    fun newId(): String = java.util.UUID.randomUUID().toString()
}

/** Process/session construction seam used by the repository and tests. */
internal class DiagnosticsSessionOwner(private val newId: () -> String = DiagnosticsSession::newId) {
    val sessionId: String = newId()
}

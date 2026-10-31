package com.yp.luminote.app.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Application-owned, local-only runtime diagnostics. Producers never write to disk. */
enum class DiagnosticSeverity { DEBUG, INFO, WARN, ERROR, FATAL }

/** This deliberately small catalog is the privacy admission boundary. */
enum class DiagnosticEventId(val component: String, val allowedMetadata: Set<String>) {
    APP_STARTED("application", setOf("apiLevel")),
    APP_EXIT_OBSERVED("application", setOf("reason", "importance", "traceHash", "traceExcerpt")),
    NOTIFICATION_LISTENER_CONNECTED("notification", emptySet()),
    NOTIFICATION_LISTENER_DISCONNECTED("notification", emptySet()),
    NOTIFICATION_RECEIVED("notification", emptySet()),
    NOTIFICATION_ACCEPTED("notification", emptySet()),
    NOTIFICATION_REJECTED("notification", setOf("reason")),
    OVERLAY_SERVICE_CREATED("overlay", emptySet()),
    OVERLAY_SERVICE_DESTROYED("overlay", emptySet()),
    OVERLAY_ATTACHED("overlay", setOf("mode")),
    OVERLAY_DETACHED("overlay", emptySet()),
    ACCESSIBILITY_SERVICE_CONNECTED("accessibility", emptySet()),
    ACCESSIBILITY_SERVICE_DISCONNECTED("accessibility", emptySet()),
    ACCESSIBILITY_PRESENTATION_REQUESTED("accessibility", emptySet()),
    ACCESSIBILITY_OVERLAY_ATTACHED("accessibility", emptySet()),
    ACCESSIBILITY_OVERLAY_DETACHED("accessibility", emptySet()),
    ACCESSIBILITY_OVERLAY_FAILED("accessibility", setOf("errorType")),
    RENDERING_STARTED("renderer", setOf("backend")),
    RENDERING_STOPPED("renderer", setOf("reason")),
    RENDERING_ERROR("renderer", setOf("errorType")),
    RENDERING_STALLED("renderer", setOf("expected", "inactiveMs")),
    RESONANCE_STARTED("resonance", emptySet()),
    RESONANCE_STOPPED("resonance", setOf("reason")),
    RESONANCE_AUDIO_ACTIVITY("resonance", emptySet()),
    CRASH_CAPTURED("crash", setOf("exceptionType", "thread"));
}

data class DiagnosticsStatus(
    val sessionId: String,
    val storedBytes: Long,
    val warningCount: Long,
    val errorCount: Long,
    val droppedEvents: Long,
    val storageDegraded: Boolean
)

data class DiagnosticsExportResult(val eventCount: Int, val bytes: Long)

object Diagnostics {
    @Volatile private var repository: DiagnosticsRepository? = null
    internal fun install(value: DiagnosticsRepository) { repository = value }
    fun record(
        event: DiagnosticEventId,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        message: String = event.name,
        metadata: Map<String, String> = emptyMap(),
        correlationId: String? = null,
        throwable: Throwable? = null
    ) = repository?.record(event, severity, message, metadata, correlationId, throwable) ?: Unit
    fun markActivity(correlationId: String) = repository?.markActivity(correlationId) ?: Unit
    fun expectRendering(correlationId: String, expected: Boolean) = repository?.expectRendering(correlationId, expected) ?: Unit
    fun status(): DiagnosticsStatus? = repository?.status()
}

class DiagnosticsRepository private constructor(private val appContext: Context) {
    private val root = File(appContext.filesDir, "diagnostics")
    private val active = File(root, "events-current.jsonl")
    /* The repository and JVM tests intentionally share this file mutation primitive. */
    private val journal = DiagnosticsJournal(root, MAX_FILE_BYTES, MAX_TOTAL_BYTES)
    private val crashes = File(root, "crashes.jsonl")
    private val queue = ArrayBlockingQueue<DiagnosticRecord>(256)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "luminote-diagnostics").apply { isDaemon = true } }
    private val healthScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "luminote-diagnostics-health").apply { isDaemon = true } }
    private val session = DiagnosticsSessionOwner()
    private val sessionId get() = session.sessionId
    private val dropped = AtomicLong()
    private val warnings = AtomicLong()
    private val errors = AtomicLong()
    private val degraded = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val expectations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val closed = AtomicBoolean(false)
    private val drainScheduled = AtomicBoolean(false)
    private val healthScheduled = AtomicBoolean(false)
    @Volatile private var lastHealthCheckMs = 0L

    init {
        root.mkdirs()
        worker.execute { journal.enforceRetention(RETENTION_MS) }
    }

    fun record(event: DiagnosticEventId, severity: DiagnosticSeverity = DiagnosticSeverity.INFO, message: String = event.name,
        metadata: Map<String, String> = emptyMap(), correlationId: String? = null, throwable: Throwable? = null) {
        if (closed.get()) return
        val admitted = DiagnosticsPolicy.admittedMetadata(event, metadata)
        val record = DiagnosticRecord(Instant.now().toString(), SystemClock.elapsedRealtimeNanos(), sessionId, severity, event, sanitize(message), admitted, sanitizeCorrelation(correlationId), throwable?.let(::safeThrowable))
        if (!queue.offer(record)) dropped.incrementAndGet() else scheduleDrain()
        if (severity == DiagnosticSeverity.WARN) warnings.incrementAndGet()
        if (severity == DiagnosticSeverity.ERROR || severity == DiagnosticSeverity.FATAL) errors.incrementAndGet()
    }

    /** The token was validated when its expectation was armed; keep render/audio callbacks allocation-free. */
    fun markActivity(correlationId: String) { expectations.computeIfPresent(correlationId) { _, _ -> SystemClock.elapsedRealtime() } }
    fun expectRendering(correlationId: String, expected: Boolean) {
        val safe = sanitizeCorrelation(correlationId) ?: return
        if (expected) {
            expectations[safe] = SystemClock.elapsedRealtime()
            scheduleHealthCheck()
        } else expectations.remove(safe)
    }
    fun checkHealth(timeoutMs: Long = 5_000L) {
        val now = SystemClock.elapsedRealtime()
        expectations.forEach { (id, last) -> if (now - last >= timeoutMs) {
            record(DiagnosticEventId.RENDERING_STALLED, DiagnosticSeverity.WARN, "Expected renderer has no frame activity", mapOf("expected" to "rendering", "inactiveMs" to (now - last).toString()), id)
            expectations[id] = now
        } }
    }

    fun status(): DiagnosticsStatus = DiagnosticsStatus(sessionId, directoryBytes(root), warnings.get(), errors.get(), dropped.get(), degraded.get())

    suspend fun export(uri: Uri): DiagnosticsExportResult = withContext(Dispatchers.IO) {
        val snapshot = withContext(Dispatchers.IO) { worker.submit<Snapshot> { snapshotLocked() }.get() }
        try {
            appContext.contentResolver.openOutputStream(uri)?.use { output ->
                val version = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"
                DiagnosticsArchive.writeDiagnostics(
                    output = output,
                    manifest = "{\"schemaVersion\":1,\"appVersion\":\"${json(version)}\",\"exportedAt\":\"${Instant.now()}\",\"limitations\":\"Crash capture is best effort; native crashes, force stops, process kills and some ANRs may not invoke it.\"}",
                    device = "{\"sdkInt\":${Build.VERSION.SDK_INT},\"manufacturer\":\"${json(Build.MANUFACTURER)}\",\"model\":\"${json(Build.MODEL)}\"}",
                    events = snapshot.events,
                    crashes = snapshot.crashes,
                    health = status().toJson(),
                    beforeBuffer = { coroutineContext.ensureActive() }
                )
            } ?: error("Unable to open selected diagnostics destination")
            DiagnosticsExportResult(snapshot.events.readLines().size, directoryBytes(snapshot.directory))
        } finally { snapshot.directory.deleteRecursively() }
    }

    fun clear() { worker.execute { drainQueue(); clearLocked() } }
    suspend fun clearAndAwait() = withContext(Dispatchers.IO) {
        worker.submit {
            drainQueue()
            clearLocked()
        }.get()
    }
    fun flush(timeoutMs: Long = 200L) { worker.submit { drainQueue() }.get(timeoutMs, TimeUnit.MILLISECONDS) }
    fun recordCrash(thread: Thread, throwable: Throwable) {
        val line = DiagnosticRecord(Instant.now().toString(), SystemClock.elapsedRealtimeNanos(), sessionId, DiagnosticSeverity.FATAL, DiagnosticEventId.CRASH_CAPTURED,
            "Uncaught exception", mapOf("exceptionType" to throwable.javaClass.simpleName, "thread" to sanitize(thread.name)), null, safeThrowable(throwable)).toJson() + "\n"
        runCatching {
            root.mkdirs()
            // Crash capture must remain bounded too; no recursive diagnostic event on failure.
            if (crashes.length() + line.length <= MAX_CRASH_BYTES && directoryBytes(root) + line.length <= MAX_EXPORT_BYTES) {
                crashes.appendText(line)
            }
        }
    }
    fun inspectPreviousExit() {
        if (Build.VERSION.SDK_INT < 30) return
        val manager = appContext.getSystemService(android.app.ActivityManager::class.java) ?: return
        manager.getHistoricalProcessExitReasons(appContext.packageName, 0, 1).firstOrNull()?.let { exit ->
            val trace = runCatching {
                exit.traceInputStream?.bufferedReader()?.use { reader ->
                    /* OEMs may omit this stream. Never allocate an unbounded ANR trace on startup. */
                    val buffer = CharArray(MAX_TRACE_EXCERPT_CHARS)
                    val count = reader.read(buffer, 0, buffer.size)
                    if (count > 0) sanitize(String(buffer, 0, count)) else null
                }
            }.getOrNull()
            val traceHash = trace?.hashCode()?.toUInt()?.toString(16)
            val fingerprint = "${exit.timestamp}:${exit.reason}:${exit.importance}:$traceHash"
            val prefs = appContext.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
            if (prefs.getString("last_exit", null) != fingerprint) {
                prefs.edit().putString("last_exit", fingerprint).apply()
                record(DiagnosticEventId.APP_EXIT_OBSERVED, DiagnosticSeverity.WARN, "Previous process exit observed", buildMap {
                    put("reason", exit.reason.toString()); put("importance", exit.importance.toString())
                    traceHash?.let { put("traceHash", it) }
                    trace?.takeIf { it.isNotBlank() }?.let { put("traceExcerpt", it) }
                })
            }
        }
    }
    fun close() { if (closed.compareAndSet(false, true)) { healthScheduler.shutdownNow(); worker.shutdown() } }

    private fun scheduleHealthCheck() {
        if (healthScheduled.compareAndSet(false, true)) healthScheduler.schedule({
            worker.execute {
                try { checkHealth() } finally {
                    healthScheduled.set(false)
                    if (expectations.isNotEmpty() && !closed.get()) scheduleHealthCheck()
                }
            }
        }, HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) worker.execute {
            try { drainQueue() } finally {
                drainScheduled.set(false)
                if (queue.isNotEmpty()) scheduleDrain()
            }
        }
    }
    /** Runs only on [worker], so persistence, rotation, snapshot and clear are serialized. */
    private fun drainQueue() {
        while (true) {
            val item = queue.poll() ?: break
            persist(item)
        }
    }
    private fun persist(record: DiagnosticRecord) { if (!journal.append(record.toJson())) degraded.set(true) }
    private fun clearLocked() {
        generation.incrementAndGet()
        journal.clear()
    }
    /** Called by the single writer after pending records have been persisted. */
    private fun snapshotLocked(): Snapshot {
        drainQueue()
        val dir = File(appContext.cacheDir, "diagnostics-export-${System.nanoTime()}").apply { mkdirs() }
        val events = File(dir, "events.jsonl")
        val crashBudget = crashes.takeIf(File::exists)?.length()?.coerceAtMost(MAX_CRASH_BYTES) ?: 0L
        val eventBudget = MAX_EXPORT_BYTES - crashBudget
        journal.writeMergedSnapshot(events, eventBudget)
        val crashCopy = crashes.takeIf(File::exists)?.let { File(dir, "crashes.jsonl").also { target -> it.copyTo(target, true) } }
        return Snapshot(dir, events, crashCopy)
    }
    private data class Snapshot(val directory: File, val events: File, val crashes: File?)
    companion object {
        @Volatile private var instance: DiagnosticsRepository? = null
        fun get(context: Context): DiagnosticsRepository = instance ?: synchronized(this) {
            instance ?: DiagnosticsRepository(context.applicationContext).also { instance = it; Diagnostics.install(it) }
        }
        private const val MAX_FILE_BYTES = 1_024 * 1_024L
        private const val MAX_TOTAL_BYTES = 20L * 1_024 * 1_024
        private const val MAX_EXPORT_BYTES = MAX_TOTAL_BYTES
        private const val MAX_CRASH_BYTES = 1L * 1_024 * 1_024
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
        private const val HEALTH_CHECK_INTERVAL_MS = 1_000L
        private const val MAX_TRACE_EXCERPT_CHARS = 4_096
    }
}

internal data class DiagnosticRecord(val utc: String, val monotonicNanos: Long, val sessionId: String, val severity: DiagnosticSeverity, val event: DiagnosticEventId, val message: String, val metadata: Map<String, String>, val correlationId: String?, val exception: String?) {
    fun toJson(): String = "{\"schemaVersion\":1,\"utc\":\"${json(utc)}\",\"monotonicNanos\":$monotonicNanos,\"sessionId\":\"${json(sessionId)}\",\"severity\":\"$severity\",\"component\":\"${event.component}\",\"eventId\":\"${event.name}\",\"message\":\"${json(message)}\",\"metadata\":{${metadata.entries.joinToString(",") { "\"${json(it.key)}\":\"${json(it.value)}\"" }}},\"correlationId\":${correlationId?.let { "\"${json(it)}\"" } ?: "null"},\"exception\":${exception?.let { "\"${json(it)}\"" } ?: "null"}}"
}
private fun DiagnosticsStatus.toJson() = "{\"sessionId\":\"${json(sessionId)}\",\"storedBytes\":$storedBytes,\"warningCount\":$warningCount,\"errorCount\":$errorCount,\"droppedEvents\":$droppedEvents,\"storageDegraded\":$storageDegraded}"
private fun directoryBytes(directory: File): Long = directory.walkTopDown().filter(File::isFile).sumOf(File::length)
private fun sanitize(value: String): String = value.take(512).replace(Regex("(?i)(token|secret|password|authorization)\\s*[=:]\\s*[^\\s,]+"), "$1=[redacted]").replace(Regex("[\\r\\n]"), " ")
internal fun sanitizeCorrelation(value: String?): String? = value?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,96}")) }
private fun safeThrowable(value: Throwable): String = buildString { append(value.javaClass.name); value.stackTrace.take(24).forEach { append("\\n at ").append(it.className).append('.').append(it.methodName).append(':').append(it.lineNumber) } }
private fun isSafe(value: String): Boolean = !value.contains(Regex("(?i)(token|secret|password|authorization|@)"))
/** Pure policy used by the writer and JVM tests; file mutation stays owned by [DiagnosticsRepository]. */
internal object DiagnosticsPolicy {
    fun admittedMetadata(event: DiagnosticEventId, metadata: Map<String, String>): Map<String, String> =
        metadata.filter { (key, value) ->
            key in event.allowedMetadata &&
                value.length <= (if (key == "traceExcerpt") 4_096 else 160) &&
                isSafe(value)
        }

    fun retainedFiles(files: List<JournalFile>, nowMs: Long, maxTotalBytes: Long, retentionMs: Long): List<JournalFile> {
        val eligible = files.filter { it.active || it.lastModifiedMs >= nowMs - retentionMs }.sortedBy { it.lastModifiedMs }.toMutableList()
        var bytes = eligible.sumOf { it.bytes }
        eligible.filterNot { it.active }.forEach { file ->
            if (bytes > maxTotalBytes) {
                eligible.remove(file)
                bytes -= file.bytes
            }
        }
        return eligible
    }
}

internal data class JournalFile(val name: String, val bytes: Long, val lastModifiedMs: Long, val active: Boolean)
private fun json(value: String): String = buildString(value.length + 16) {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
}

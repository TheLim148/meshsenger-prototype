package com.example.meshsenger.mesh.logging

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * Общий debug-логгер для MVP.
 *
 * Пишет события одновременно:
 * 1) в Logcat Android Studio;
 * 2) в StateFlow, чтобы DebugScreen мог показать логи на телефоне;
 * 3) в SharedPreferences, чтобы логи не пропадали после краша приложения.
 */
object MeshLogger {
    private const val MAX_LOG_ENTRIES = 1000
    private const val PREFS_NAME = "meshsenger_debug_logs"
    private const val KEY_LOGS = "logs"

    private val lock = Any()
    private val nextId = AtomicLong(1L)
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    private var prefs: SharedPreferences? = null
    private var crashHandlerInstalled = false

    private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()

    fun install(context: Context) {
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val restored = decodeLogs(prefs?.getString(KEY_LOGS, null)).takeLast(MAX_LOG_ENTRIES)
                if (restored.isNotEmpty()) {
                    _logs.value = restored
                    nextId.set((restored.maxOfOrNull { it.id } ?: 0L) + 1L)
                }
            }

            if (!crashHandlerInstalled) {
                crashHandlerInstalled = true
                val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                    runCatching {
                        error(
                            tag = "UncaughtException",
                            message = "Критический краш в потоке ${thread.name}: ${error.localizedMessage ?: error.javaClass.simpleName}",
                            throwable = error,
                        )
                    }

                    if (previousCrashHandler != null) {
                        previousCrashHandler.uncaughtException(thread, error)
                    } else {
                        Process.killProcess(Process.myPid())
                        exitProcess(10)
                    }
                }
            }
        }
    }

    fun info(tag: String, message: String) {
        Log.d(tag, message)
        append(level = DebugLogLevel.Info, tag = tag, message = message)
    }

    fun warning(tag: String, message: String) {
        Log.w(tag, message)
        append(level = DebugLogLevel.Warning, tag = tag, message = message)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, throwable)
        }

        val fullMessage = if (throwable == null) {
            message
        } else {
            "$message\n${throwable.stackTraceToStringSafe()}"
        }

        append(level = DebugLogLevel.Error, tag = tag, message = fullMessage)
    }

    fun clear() {
        _logs.value = emptyList()
        prefs?.edit()?.remove(KEY_LOGS)?.commit()
        Log.d("MeshLogger", "Debug logs cleared")
    }

    private fun append(level: DebugLogLevel, tag: String, message: String) {
        val entry = DebugLogEntry(
            id = nextId.getAndIncrement(),
            timeText = formatter.format(Instant.now()),
            level = level,
            tag = tag,
            message = message,
        )

        synchronized(lock) {
            _logs.value = (_logs.value + entry).takeLast(MAX_LOG_ENTRIES)
            persistLogsLocked()
        }
    }

    private fun persistLogsLocked() {
        val encoded = encodeLogs(_logs.value)
        prefs?.edit()?.putString(KEY_LOGS, encoded)?.commit()
    }

    private fun encodeLogs(logs: List<DebugLogEntry>): String {
        return logs.joinToString(separator = "\n") { entry ->
            listOf(
                entry.id.toString(),
                entry.timeText.encodeBase64(),
                entry.level.name,
                entry.tag.encodeBase64(),
                entry.message.encodeBase64(),
            ).joinToString(separator = "|")
        }
    }

    private fun decodeLogs(raw: String?): List<DebugLogEntry> {
        if (raw.isNullOrBlank()) return emptyList()

        return raw.lineSequence().mapNotNull { line ->
            runCatching {
                val parts = line.split("|", limit = 5)
                if (parts.size != 5) return@runCatching null

                DebugLogEntry(
                    id = parts[0].toLong(),
                    timeText = parts[1].decodeBase64(),
                    level = DebugLogLevel.valueOf(parts[2]),
                    tag = parts[3].decodeBase64(),
                    message = parts[4].decodeBase64(),
                )
            }.getOrNull()
        }.filterNotNull().toList()
    }

    private fun String.encodeBase64(): String {
        return Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun String.decodeBase64(): String {
        return String(Base64.decode(this, Base64.NO_WRAP), Charsets.UTF_8)
    }

    private fun Throwable.stackTraceToStringSafe(): String {
        return runCatching {
            val writer = StringWriter()
            printStackTrace(PrintWriter(writer))
            writer.toString()
        }.getOrDefault("${javaClass.simpleName}: ${localizedMessage}")
    }
}

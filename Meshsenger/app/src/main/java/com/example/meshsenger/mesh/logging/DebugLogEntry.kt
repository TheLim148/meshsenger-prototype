package com.example.meshsenger.mesh.logging

/**
 * Одна строка debug-лога, которую можно показать прямо внутри приложения.
 */
data class DebugLogEntry(
    val id: Long,
    val timeText: String,
    val level: DebugLogLevel,
    val tag: String,
    val message: String,
)

enum class DebugLogLevel {
    Info,
    Warning,
    Error,
}

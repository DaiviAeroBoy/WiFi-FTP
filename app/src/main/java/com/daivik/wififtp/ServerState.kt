package com.daivik.wififtp

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class Mode { FULL, FOLDER, FILE }

data class ClientInfo(val ip: String, val host: String, val connections: Int)

data class Pending(val ip: String, val host: String)

data class UiState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val url: String = "",
    val pin: String? = null,
    val clients: List<ClientInfo> = emptyList(),
    val pending: List<Pending> = emptyList(),
    val transfers: List<String> = emptyList(),
    val upSpeed: Long = 0,    // PC -> phone, bytes/s
    val downSpeed: Long = 0,  // phone -> PC, bytes/s
    val error: String? = null
)

object ServerState {
    val state = MutableStateFlow(UiState())
}

/** Per-session allow / block decisions (cleared on Disconnect). */
object Access {
    val allowed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val blocked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun reset() { allowed.clear(); blocked.clear() }
}

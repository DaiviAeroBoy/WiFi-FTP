package com.daivik.wififtp

import kotlinx.coroutines.flow.MutableStateFlow

enum class Mode { FULL, FOLDER, FILE }

data class ClientInfo(val ip: String, val host: String, val connections: Int)

data class UiState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val url: String = "",
    val pin: String? = null,
    val clients: List<ClientInfo> = emptyList(),
    val error: String? = null
)

object ServerState {
    val state = MutableStateFlow(UiState())
}

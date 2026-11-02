package com.example.ui

sealed class ConnectionStatus {
    data object Disconnected : ConnectionStatus()
    data object Connecting : ConnectionStatus()
    data class Connected(val organization: String) : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

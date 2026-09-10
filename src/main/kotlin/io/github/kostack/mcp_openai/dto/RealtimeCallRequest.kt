package io.github.kostack.mcp_openai.dto

data class RealtimeCallRequest(
  val sdp: String,
  val namespace: String,
  val channel: String,
  val language: String,
  val audioEnabled: Boolean = false
)

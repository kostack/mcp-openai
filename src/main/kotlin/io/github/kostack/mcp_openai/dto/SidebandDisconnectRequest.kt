package io.github.kostack.mcp_openai.dto

data class SidebandDisconnectRequest(
  val callId: String,
  val namespace: String,
  val channel: String
)

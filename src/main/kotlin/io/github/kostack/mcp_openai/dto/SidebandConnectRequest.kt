package io.github.kostack.mcp_openai.dto

data class SidebandConnectRequest(
  val callId: String,
  val clientSecret: String,
  val namespace: String,
  val channel: String,
  val language: String,
  var audioEnabled: Boolean = false
) {
  val modality: String
    get() = if (audioEnabled) "audio" else "text"
}

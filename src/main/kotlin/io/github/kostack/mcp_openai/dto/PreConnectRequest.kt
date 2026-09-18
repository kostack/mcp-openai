package io.github.kostack.mcp_openai.dto

data class PreConnectRequest(
  val namespace: String,
  val channel: String,
  val language: String,
  var audioEnabled: Boolean = false
)

package io.github.kostack.mcp_openai.autoconfiguration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "kostack-mcp")
data class McpProperties(
  var apiKey: String = "",
  var sidebandUrl: String = "wss://api.openai.com/v1/realtime",
  var sidebandPrefix: String = "/api/realtime",
  var sidebandMaxFramePayloadLength: Int = 1024 * 1024,
  var model: String = "gpt-realtime-mini",
  var transcriptionModel: String = "gpt-realtime-whisper",
  var enableAudio: Boolean = false,
  var callsUrl: String = "https://api.openai.com/v1/realtime/calls"
)

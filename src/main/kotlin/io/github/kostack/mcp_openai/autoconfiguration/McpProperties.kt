package io.github.kostack.mcp_openai.autoconfiguration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "kostack-mcp")
data class McpProperties(
  var apiKey: String = "",
  var clientSecretsUrl: String = "https://api.openai.com/v1/realtime/client_secrets",
  var sidebandUrl: String = "wss://api.openai.com/v1/realtime",
  val sidebandPrefix: String = "/api/realtime",
  val sidebandMaxFramePayloadLength: Int =
    1024 * 1024,
  var model: String = "gpt-realtime-mini",
  val transcriptionModel: String = "gpt-realtime-whisper",
  val enableAudio: Boolean = false
)

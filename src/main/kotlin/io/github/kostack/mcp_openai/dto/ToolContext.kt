package io.github.kostack.mcp_openai.dto

import io.github.kostack.mcp_openai.serialization.JsonSerializer

data class ToolContext(
  val namespace: String,
  val channel: String,
  val sessionId: String,
  var rawRequest: String? = null,
  val metadata: MutableMap<String, Any> = mutableMapOf()
) {
  inline fun <reified T> getRequest(): T = JsonSerializer.decode(rawRequest!!)
}

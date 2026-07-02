package io.github.kostack.mcp_openai.dto

import com.fasterxml.jackson.annotation.JsonIgnore

data class ToolDefinition(
  @JsonIgnore val namespace: String,
  val type: String = "function",
  val name: String,
  val description: String,
  val parameters: Map<String, Any>
)

package io.github.kostack.mcp_openai.dto

data class ToolResult(
  val success: Boolean,
  val result: Any? = null
)

package io.github.kostack.mcp_openai.tool

import io.github.kostack.mcp_openai.dto.ToolContext
import io.github.kostack.mcp_openai.dto.ToolDefinition
import io.github.kostack.mcp_openai.dto.ToolResult

interface Tool {
  suspend fun execute(context: ToolContext): ToolResult

  fun getDefinition(): ToolDefinition
}

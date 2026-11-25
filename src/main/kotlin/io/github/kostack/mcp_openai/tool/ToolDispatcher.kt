package io.github.kostack.mcp_openai.tool

import io.github.kostack.mcp_openai.dto.ToolContext
import io.github.kostack.mcp_openai.dto.ToolDefinition
import io.github.kostack.mcp_openai.dto.ToolResult

interface ToolDispatcher {
  suspend fun execute(
    name: String,
    context: ToolContext
  ): ToolResult

  fun getDefinitions(namespace: String): List<ToolDefinition>
}

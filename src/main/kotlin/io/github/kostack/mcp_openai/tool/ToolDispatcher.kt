package io.github.kostack.mcp_openai.tool

import io.github.kostack.mcp_openai.dto.ToolContext
import io.github.kostack.mcp_openai.dto.ToolDefinition
import io.github.kostack.mcp_openai.dto.ToolResult
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ToolDispatcher(
  private val tools: List<Tool>
) {
  suspend fun execute(
    name: String,
    context: ToolContext
  ): ToolResult {
    try {
      val tool =
        tools.find {
          it.getDefinition().name == name && it.getDefinition().namespace == context.namespace
        } ?: error("Unknown tool: $name")
      return tool.execute(context)
    } catch (e: CancellationException) {
      val cause = e.cause
      if (cause != null) {
        log.warn("Tool execution cancelled: $name due to: ${cause.message}", cause)
      } else {
        log.info("Tool execution cancelled: $name (no cause)")
      }
      throw e
    } catch (e: Exception) {
      log.error("Error executing tool: $name, args: ${context.rawRequest}, error: ${e.message}", e)
      return ToolResult(success = false, "Error executing tool: $name, error: ${e.message}")
    }
  }

  fun getDefinitions(namespace: String): List<ToolDefinition> =
    tools.filter { it.getDefinition().namespace == namespace }.map { it.getDefinition() }

  companion object {
    val log: Logger = LoggerFactory.getLogger(ToolDispatcher::class.java)
  }
}

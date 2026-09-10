package io.github.kostack.mcp_openai.mcp

import io.github.kostack.mcp_openai.dto.ToolContext
import io.github.kostack.mcp_openai.dto.ToolDefinition
import io.github.kostack.mcp_openai.dto.ToolResult
import io.github.kostack.mcp_openai.tool.AbstractTool
import io.github.kostack.mcp_openai.utils.ToolSchemaUtils
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.seconds

@Component
class Test2Tool : AbstractTool() {
  override val namespace: String = "default"
  override val toolName: String = "describe_kostack"
  override val description: String =
    """
    Describes KoStack
    """.trimIndent()

  override fun getDefinition(): ToolDefinition =
    ToolDefinition(
      namespace = namespace,
      name = toolName,
      description = description,
      parameters = ToolSchemaUtils.emptyParameters()
    )

  override suspend fun execute(context: ToolContext): ToolResult {
    delay(20.seconds)
    return ToolResult(
      success = true,
      result = "KoStack is a tool created by Niko"
    )
  }
}

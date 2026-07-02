package io.github.kostack.mcp_openai.mcp

import io.github.kostack.mcp_openai.dto.ToolContext
import io.github.kostack.mcp_openai.dto.ToolDefinition
import io.github.kostack.mcp_openai.dto.ToolResult
import io.github.kostack.mcp_openai.tool.AbstractTool
import io.github.kostack.mcp_openai.utils.ToolSchemaUtils
import org.springframework.stereotype.Component

@Component
class TestTool : AbstractTool() {
  override val namespace: String = "default"
  override val toolName: String = "find_person_by_country_code"
  override val description: String =
    """
    Finds the person according to provided country.
    """.trimIndent()

  override fun getDefinition(): ToolDefinition =
    ToolDefinition(
      namespace = namespace,
      name = toolName,
      description = description,
      parameters = ToolSchemaUtils.toParameters<TestRequest>()
    )

  override suspend fun execute(context: ToolContext): ToolResult {
    val request = context.getRequest<TestRequest>()

    val place =
      when (request.countryCode) {
        "DE" -> {
          "Daniel"
        }

        "GR" -> {
          "Nikolay"
        }

        else -> {
          "No Idea"
        }
      }
    val result = TestResult(place)
    val success = place != "No Idea"

    return ToolResult(
      success = success,
      result = result
    )
  }
}

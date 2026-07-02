package io.github.kostack.mcp_openai.mcp

import kotlinx.serialization.Serializable
import org.springframework.ai.tool.annotation.ToolParam

@Serializable
data class TestRequest(
  @ToolParam(description = "Provide the country code")
  val countryCode: String
)

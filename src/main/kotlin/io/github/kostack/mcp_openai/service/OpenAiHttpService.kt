package io.github.kostack.mcp_openai.service

import io.github.kostack.mcp_openai.configuration.McpProperties
import io.github.kostack.mcp_openai.dto.RealtimeTokenResponse
import io.github.kostack.mcp_openai.dto.ToolDefinition
import io.github.kostack.mcp_openai.utils.RealtimeUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

class OpenAiHttpService(
  private val webClient: WebClient,
  private val mcpProperties: McpProperties
) {
  suspend fun createEphemeralToken(
    instructions: String,
    language: String,
    definitions: List<ToolDefinition>
  ): RealtimeTokenResponse {
    val response =
      webClient
        .post()
        .uri(mcpProperties.clientSecretsUrl)
        .header(HttpHeaders.AUTHORIZATION, "Bearer ${mcpProperties.apiKey}")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
          RealtimeUtils.createSession(
            instructions = instructions,
            language = language,
            definitions = definitions,
            properties = mcpProperties
          )
        ).retrieve()
        .awaitBody<Map<String, Any>>()

    val clientSecret =
      ((response["value"] ?: response["client_secret"]) as? String)
        ?: error("No client secret returned from OpenAI")

    return RealtimeTokenResponse(clientSecret)
  }
}

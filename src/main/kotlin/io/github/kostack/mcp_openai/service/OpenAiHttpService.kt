package io.github.kostack.mcp_openai.service

import io.github.kostack.mcp_openai.autoconfiguration.McpProperties
import io.github.kostack.mcp_openai.dto.RealtimeCallResponse
import io.github.kostack.mcp_openai.dto.RealtimeTokenResponse
import io.github.kostack.mcp_openai.dto.ToolDefinition
import io.github.kostack.mcp_openai.utils.HttpRetryUtils
import io.github.kostack.mcp_openai.utils.RealtimeUtils
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

class OpenAiHttpService(
  private val webClient: WebClient,
  private val mcpProperties: McpProperties
) {
  suspend fun createCall(
    sdp: String,
    instructions: String,
    language: String,
    definitions: List<ToolDefinition>
  ): RealtimeCallResponse {
    val parts =
      MultipartBodyBuilder().apply {
        part("sdp", sdp)
        part(
          "session",
          RealtimeUtils.createSession(instructions, language, definitions, mcpProperties).getValue("session")
        ).contentType(MediaType.APPLICATION_JSON)
      }
    val response =
      webClient
        .post()
        .uri(mcpProperties.callsUrl)
        .header(HttpHeaders.AUTHORIZATION, "Bearer ${mcpProperties.apiKey}")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .bodyValue(parts.build())
        .retrieve()
        .toEntity(String::class.java)
        .awaitSingle()
    val callId =
      response.headers.location
        ?.path
        ?.let { Regex("/v1/realtime/calls/([^/]+)").matchEntire(it)?.groupValues?.get(1) }
        ?: error("OpenAI call response has no valid Location header")
    val answer =
      response.body?.takeIf { it.isNotBlank() }
        ?: error("OpenAI call response has no SDP answer")
    return RealtimeCallResponse(answer, callId)
  }

  suspend fun createEphemeralToken(
    instructions: String,
    language: String,
    definitions: List<ToolDefinition>
  ): RealtimeTokenResponse {
    val response =
      HttpRetryUtils.retryHttpCall {
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
      }

    val clientSecret =
      ((response["value"] ?: response["client_secret"]) as? String)
        ?: error("No client secret returned from OpenAI")

    return RealtimeTokenResponse(clientSecret)
  }
}

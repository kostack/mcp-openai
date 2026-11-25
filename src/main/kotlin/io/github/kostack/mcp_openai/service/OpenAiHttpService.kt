package io.github.kostack.mcp_openai.service

import io.github.kostack.mcp_openai.dto.RealtimeCallResponse
import io.github.kostack.mcp_openai.dto.ToolDefinition

interface OpenAiHttpService {
  suspend fun createCall(
    sdp: String,
    instructions: String,
    language: String,
    definitions: List<ToolDefinition>
  ): RealtimeCallResponse

  suspend fun disconnect(callId: String)
}

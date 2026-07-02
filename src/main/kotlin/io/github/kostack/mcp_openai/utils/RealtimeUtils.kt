package io.github.kostack.mcp_openai.utils

import io.github.kostack.mcp_openai.configuration.McpProperties
import io.github.kostack.mcp_openai.dto.ToolDefinition

object RealtimeUtils {
  fun createSession(
    instructions: String,
    language: String,
    definitions: List<ToolDefinition>,
    properties: McpProperties
  ): Map<String, Any> =
    mapOf(
      "session" to
        mapOf(
          "type" to "realtime",
          "model" to properties.model,
          "instructions" to instructions,
          "tools" to definitions,
          "tool_choice" to "auto",
          "audio" to
            mapOf(
              "input" to
                mapOf(
                  "turn_detection" to
                    mapOf(
                      "type" to "server_vad",
                      "create_response" to true,
                      "interrupt_response" to true,
                      "threshold" to 0.5,
                      "prefix_padding_ms" to 300,
                      "silence_duration_ms" to 800
                    ),
                  "transcription" to
                    mapOf(
                      "model" to properties.transcriptionModel,
                      "language" to language,
                      "delay" to "medium"
                    )
                ),
              "output" to
                mapOf(
                  "voice" to "alloy"
                )
            )
        )
    )

  fun conversationFunctionOutput(
    toolCallId: String,
    result: String
  ): Map<String, Any> =
    mapOf(
      "type" to "conversation.item.create",
      "item" to
        mapOf(
          "type" to "function_call_output",
          "call_id" to toolCallId,
          "output" to result
        )
    )

  fun createResponse(
    modality: String = "text",
    instructions: String = ""
  ): Map<String, Any> =
    mapOf(
      "type" to "response.create",
      "response" to
        mapOf(
          "instructions" to instructions,
          "output_modalities" to listOf(modality)
        )
    )
}

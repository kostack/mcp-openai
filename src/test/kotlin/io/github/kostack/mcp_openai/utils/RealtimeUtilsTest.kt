package io.github.kostack.mcp_openai.utils

import io.github.kostack.mcp_openai.configuration.McpProperties
import io.github.kostack.mcp_openai.dto.ToolDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

class RealtimeUtilsTest {
  @Test
  fun `createSession builds realtime session payload`() {
    val tool =
      ToolDefinition(
        namespace = "crm",
        name = "lookup_account",
        description = "Looks up an account",
        parameters = mapOf("type" to "object")
      )
    val properties =
      McpProperties(
        model = "gpt-realtime",
        transcriptionModel = "gpt-transcribe"
      )

    val payload =
      RealtimeUtils.createSession(
        instructions = "Reply in English",
        language = "en",
        definitions = listOf(tool),
        properties = properties
      )
    val session = payload["session"] as Map<*, *>
    val audio = session["audio"] as Map<*, *>
    val input = audio["input"] as Map<*, *>
    val transcription = input["transcription"] as Map<*, *>
    val output = audio["output"] as Map<*, *>

    assertEquals("realtime", session["type"])
    assertEquals("gpt-realtime", session["model"])
    assertEquals("Reply in English", session["instructions"])
    assertEquals(listOf(tool), session["tools"])
    assertEquals("auto", session["tool_choice"])
    assertEquals("gpt-transcribe", transcription["model"])
    assertEquals("en", transcription["language"])
    assertEquals("alloy", output["voice"])
  }
}

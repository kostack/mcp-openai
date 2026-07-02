package io.github.kostack.mcp_openai.utils

import org.springframework.ai.util.json.schema.JsonSchemaGenerator
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

object ToolSchemaUtils {
  val objectMapper = ObjectMapper()

  inline fun <reified T> toParameters(): Map<String, Any> {
    val schemaJson = JsonSchemaGenerator.generateForType(T::class.java)

    return objectMapper
      .readValue(
        schemaJson,
        object : TypeReference<MutableMap<String, Any>>() {}
      ).also { it.remove("\$schema") }
  }
}

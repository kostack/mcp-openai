package io.github.kostack.mcp_openai.utils

import io.github.kostack.mcp_openai.mcp.TestRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolSchemaUtilsTest {
  @Test
  fun `toParameters generates json schema without schema metadata`() {
    val parameters = ToolSchemaUtils.toParameters<TestRequest>()
    val properties = parameters["properties"] as Map<*, *>
    val countryCode = properties["countryCode"] as Map<*, *>

    assertEquals("object", parameters["type"])
    assertFalse(parameters.containsKey("\$schema"))
    assertTrue("countryCode" in properties)
    assertEquals("Provide the country code", countryCode["description"])
  }
}

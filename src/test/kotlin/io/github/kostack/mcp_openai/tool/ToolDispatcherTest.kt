package io.github.kostack.mcp_openai.tool

import io.github.kostack.mcp_openai.dto.ToolContext
import io.github.kostack.mcp_openai.dto.ToolDefinition
import io.github.kostack.mcp_openai.dto.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class ToolDispatcherTest {
  @Test
  fun `execute runs matching tool by name and namespace`() =
    runTest {
      val context =
        ToolContext(
          namespace = "crm",
          channel = "sideband",
          sessionId = "session-1",
          rawRequest = """{"id":1}"""
        )
      val expectedResult = ToolResult(success = true, result = "created")
      val matchingTool = tool("crm", "create_contact")
      val otherNamespaceTool = tool("support", "create_contact")
      val otherNameTool = tool("crm", "delete_contact")
      val dispatcher = ToolDispatcher(listOf(otherNamespaceTool, otherNameTool, matchingTool))

      coEvery { matchingTool.execute(context) } returns expectedResult

      val result = dispatcher.execute("create_contact", context)

      assertSame(expectedResult, result)
      coVerify(exactly = 1) { matchingTool.execute(context) }
      coVerify(exactly = 0) { otherNamespaceTool.execute(any()) }
      coVerify(exactly = 0) { otherNameTool.execute(any()) }
    }

  @Test
  fun `execute returns failed result when tool is unknown`() =
    runTest {
      val context =
        ToolContext(
          namespace = "crm",
          channel = "sideband",
          sessionId = "session-1",
          rawRequest = """{"id":1}"""
        )
      val dispatcher = ToolDispatcher(listOf(tool("support", "create_contact")))

      val result = dispatcher.execute("create_contact", context)

      assertFalse(result.success)
      assertEquals("Error executing tool: create_contact, error: Unknown tool: create_contact", result.result)
    }

  @Test
  fun `execute returns failed result when tool throws exception`() =
    runTest {
      val context =
        ToolContext(
          namespace = "crm",
          channel = "sideband",
          sessionId = "session-1",
          rawRequest = """{"id":1}"""
        )
      val matchingTool = tool("crm", "create_contact")
      val dispatcher = ToolDispatcher(listOf(matchingTool))

      coEvery { matchingTool.execute(context) } throws IllegalStateException("backend unavailable")

      val result = dispatcher.execute("create_contact", context)

      assertFalse(result.success)
      assertEquals(
        "Error executing tool: create_contact, error: backend unavailable",
        result.result
      )
    }

  @Test
  fun `execute rethrows cancellation exception`() =
    runTest {
      val context = ToolContext(namespace = "crm", channel = "sideband", sessionId = "session-1")
      val matchingTool = tool("crm", "create_contact")
      val dispatcher = ToolDispatcher(listOf(matchingTool))
      val cancellation = CancellationException("client disconnected")

      coEvery { matchingTool.execute(context) } throws cancellation

      val thrown =
        assertFailsWith<CancellationException> {
          dispatcher.execute("create_contact", context)
        }

      assertSame(cancellation, thrown)
    }

  @Test
  fun `getDefinitions returns definitions for requested namespace only`() {
    val firstCrmDefinition = definition(namespace = "crm", name = "create_contact")
    val secondCrmDefinition = definition(namespace = "crm", name = "delete_contact")
    val supportDefinition = definition(namespace = "support", name = "create_ticket")
    val firstCrmTool = tool(firstCrmDefinition)
    val secondCrmTool = tool(secondCrmDefinition)
    val supportTool = tool(supportDefinition)
    val dispatcher = ToolDispatcher(listOf(firstCrmTool, supportTool, secondCrmTool))

    val definitions = dispatcher.getDefinitions("crm")

    assertEquals(listOf(firstCrmDefinition, secondCrmDefinition), definitions)
    verify(exactly = 2) { firstCrmTool.getDefinition() }
    verify(exactly = 2) { secondCrmTool.getDefinition() }
    verify(exactly = 1) { supportTool.getDefinition() }
  }

  private fun tool(
    namespace: String,
    name: String
  ): Tool = tool(definition(namespace, name))

  private fun tool(definition: ToolDefinition): Tool =
    mockk {
      every { getDefinition() } returns definition
    }

  private fun definition(
    namespace: String,
    name: String
  ): ToolDefinition =
    ToolDefinition(
      namespace = namespace,
      name = name,
      description = "$name description",
      parameters = emptyMap()
    )
}

package io.github.kostack.mcp_openai.registry

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebandSessionRegistryTest {
  @Test
  fun `remove with job only removes matching registered job`() {
    val registry = SidebandSessionRegistry()
    val activeJob = Job()
    val staleJob = Job()

    registry.putIfAbsent("call-123", activeJob)

    registry.remove("call-123", staleJob)

    assertTrue(registry.contains("call-123"))

    registry.remove("call-123", activeJob)

    assertFalse(registry.contains("call-123"))

    activeJob.cancel()
    staleJob.cancel()
  }
}

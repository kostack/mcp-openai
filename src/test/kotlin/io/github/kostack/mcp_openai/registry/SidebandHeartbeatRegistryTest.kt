package io.github.kostack.mcp_openai.registry

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidebandHeartbeatRegistryTest {
  private val registry = SidebandHeartbeatRegistry()

  @Test
  fun `outstanding ping age returns null for unknown call id`() {
    assertNull(registry.outstandingPingAge("unknown"))
  }

  @Test
  fun `ping records outstanding ping without replacing its original time`() {
    registry.initialize("call-123")
    registry.ping("call-123")
    Thread.sleep(25)
    val ageBeforeSecondPing = assertNotNull(registry.outstandingPingAge("call-123"))

    registry.ping("call-123")

    val ageAfterSecondPing = assertNotNull(registry.outstandingPingAge("call-123"))
    assertTrue(ageAfterSecondPing >= ageBeforeSecondPing)
  }

  @Test
  fun `pong clears outstanding ping`() {
    registry.initialize("call-123")
    registry.ping("call-123")
    assertNotNull(registry.outstandingPingAge("call-123"))

    registry.pong("call-123")

    assertNull(registry.outstandingPingAge("call-123"))
  }

  @Test
  fun `remove deletes heartbeat time`() {
    registry.initialize("call-123")
    registry.ping("call-123")

    registry.remove("call-123")

    assertNull(registry.outstandingPingAge("call-123"))
  }
}

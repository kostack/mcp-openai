package io.github.kostack.mcp_openai.registry

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidebandHeartbeatRegistryTest {
  private val registry = SidebandHeartbeatRegistry()

  @Test
  fun `age returns null for unknown call id`() {
    assertNull(registry.age("unknown"))
  }

  @Test
  fun `initialize records heartbeat time`() {
    registry.initialize("call-123")

    val age = assertNotNull(registry.age("call-123"))

    assertTrue(!age.isNegative)
    assertTrue(age < Duration.ofSeconds(1))
  }

  @Test
  fun `pong refreshes heartbeat time`() {
    registry.initialize("call-123")
    Thread.sleep(25)
    val ageBeforePong = assertNotNull(registry.age("call-123"))

    registry.pong("call-123")

    val ageAfterPong = assertNotNull(registry.age("call-123"))
    assertTrue(ageAfterPong < ageBeforePong)
  }

  @Test
  fun `remove deletes heartbeat time`() {
    registry.initialize("call-123")

    registry.remove("call-123")

    assertNull(registry.age("call-123"))
  }
}

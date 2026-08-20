package io.github.kostack.mcp_openai.health

import io.github.kostack.mcp_openai.registry.SidebandHeartbeatRegistry
import io.github.kostack.mcp_openai.registry.SidebandSessionRegistry
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import kotlin.test.Test

class SidebandLivenessServiceTest {
  private val sidebandSessionRegistry = mockk<SidebandSessionRegistry>()
  private val webSocketSessionRegistry = mockk<WebSocketSessionRegistry>()
  private val heartbeatRegistry = mockk<SidebandHeartbeatRegistry>(relaxed = true)
  private val statusPublisher = mockk<SidebandStatusPublisher>(relaxed = true)

  @Test
  fun `start sends ping repeatedly`() {
    every { sidebandSessionRegistry.isActive("call-123") } returns true
    every { webSocketSessionRegistry.isOpen("call-123") } returns true
    every { heartbeatRegistry.age("call-123") } returns Duration.ZERO
    coEvery { webSocketSessionRegistry.sendPing("call-123") } returns true

    val service =
      SidebandLivenessService(
        sidebandSessionRegistry,
        webSocketSessionRegistry,
        heartbeatRegistry,
        statusPublisher,
        pingInterval = Duration.ofMillis(10)
      )

    service.start("call-123", "web")

    coVerify(timeout = 1_000, atLeast = 2) {
      webSocketSessionRegistry.sendPing("call-123")
    }
    verify(exactly = 1) { heartbeatRegistry.initialize("call-123") }

    service.stop("call-123")
  }
}

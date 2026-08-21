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
    every { heartbeatRegistry.outstandingPingAge("call-123") } returns null
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
    verify(atLeast = 2) { heartbeatRegistry.ping("call-123") }

    service.stop("call-123")
  }

  @Test
  fun `overdue pong degrades connection without stopping monitor`() {
    every { sidebandSessionRegistry.isActive("call-123") } returns true
    every { webSocketSessionRegistry.isOpen("call-123") } returns true
    every { heartbeatRegistry.outstandingPingAge("call-123") } returns Duration.ofSeconds(1)
    coEvery { webSocketSessionRegistry.sendPing("call-123") } returns true

    val service =
      SidebandLivenessService(
        sidebandSessionRegistry,
        webSocketSessionRegistry,
        heartbeatRegistry,
        statusPublisher,
        pingInterval = Duration.ofMillis(10),
        pongTimeout = Duration.ofMillis(1)
      )

    service.start("call-123", "web")

    coVerify(timeout = 1_000, exactly = 1) {
      statusPublisher.publish("call-123", "web", SidebandStatus.DEGRADED)
    }
    coVerify(timeout = 1_000, atLeast = 2) {
      webSocketSessionRegistry.sendPing("call-123")
    }
    coVerify(exactly = 0) {
      statusPublisher.publish("call-123", "web", SidebandStatus.DISCONNECTED)
    }

    service.stop("call-123")
  }

  @Test
  fun `failed ping enqueue disconnects and stops monitor`() {
    every { sidebandSessionRegistry.isActive("call-123") } returns true
    every { webSocketSessionRegistry.isOpen("call-123") } returns true
    coEvery { webSocketSessionRegistry.sendPing("call-123") } returns false

    val service = service()

    service.start("call-123", "web")

    coVerify(timeout = 1_000, exactly = 1) {
      statusPublisher.publish("call-123", "web", SidebandStatus.DISCONNECTED)
    }
    coVerify(timeout = 1_000, exactly = 1) {
      webSocketSessionRegistry.sendPing("call-123")
    }
  }

  @Test
  fun `closed websocket disconnects without enqueueing ping`() {
    every { sidebandSessionRegistry.isActive("call-123") } returns true
    every { webSocketSessionRegistry.isOpen("call-123") } returns false

    val service = service()

    service.start("call-123", "web")

    coVerify(timeout = 1_000, exactly = 1) {
      statusPublisher.publish("call-123", "web", SidebandStatus.DISCONNECTED)
    }
    coVerify(exactly = 0) { webSocketSessionRegistry.sendPing("call-123") }
  }

  private fun service(): SidebandLivenessService =
    SidebandLivenessService(
      sidebandSessionRegistry,
      webSocketSessionRegistry,
      heartbeatRegistry,
      statusPublisher,
      pingInterval = Duration.ofMillis(10)
    )
}

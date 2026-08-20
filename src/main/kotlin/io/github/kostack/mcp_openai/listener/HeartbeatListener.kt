package io.github.kostack.mcp_openai.listener

import io.github.kostack.event_dispatcher.SuspendListener
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.event.RealtimeConnectEvent
import io.github.kostack.mcp_openai.event.RealtimeDisconnectEvent
import io.github.kostack.mcp_openai.health.SidebandLivenessService

class HeartbeatListener(
  private val sidebandLivenessService: SidebandLivenessService
) {
  @SuspendListener(RealtimeEvents.SESSION_START, priority = 1000)
  fun onConnect(event: RealtimeConnectEvent) {
    val channel = event.request.channel
    val callId = event.request.callId

    sidebandLivenessService.start(callId, channel)
  }

  @SuspendListener(RealtimeEvents.DISCONNECT, priority = 1000)
  fun onDisconnect(event: RealtimeDisconnectEvent) {
    val callId = event.request.callId

    sidebandLivenessService.stop(callId)
  }
}

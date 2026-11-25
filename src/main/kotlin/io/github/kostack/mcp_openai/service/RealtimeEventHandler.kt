package io.github.kostack.mcp_openai.service

import io.github.kostack.mcp_openai.dto.RealtimeEvent
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest

interface RealtimeEventHandler {
  suspend fun handleInbound(
    event: RealtimeEvent,
    request: SidebandConnectRequest
  )

  fun cancel(callId: String)
}

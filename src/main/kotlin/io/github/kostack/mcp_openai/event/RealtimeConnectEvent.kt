package io.github.kostack.mcp_openai.event

import io.github.kostack.event_dispatcher.AppEvent
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest

data class RealtimeConnectEvent(
  val request: SidebandConnectRequest
) : AppEvent

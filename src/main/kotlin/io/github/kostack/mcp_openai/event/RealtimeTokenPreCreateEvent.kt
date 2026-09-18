package io.github.kostack.mcp_openai.event

import io.github.kostack.event_dispatcher.AppEvent
import io.github.kostack.mcp_openai.dto.PreConnectRequest

data class RealtimeTokenPreCreateEvent(
  val request: PreConnectRequest,
  var instructions: String = "You are a disabled assistant. DO NOT answer any questions."
) : AppEvent

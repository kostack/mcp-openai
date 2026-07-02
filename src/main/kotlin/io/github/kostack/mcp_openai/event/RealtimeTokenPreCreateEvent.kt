package io.github.kostack.mcp_openai.event

import io.github.kostack.event_dispatcher.AppEvent
import io.github.kostack.mcp_openai.dto.TokenRequest

data class RealtimeTokenPreCreateEvent(
  val request: TokenRequest,
  var instructions: String = "You are a disabled assistant. DO NOT answer any questions."
) : AppEvent

package io.github.kostack.mcp_openai

import io.github.kostack.mcp_openai.dto.RealtimeCallRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest
import org.springframework.web.reactive.function.server.ServerResponse

interface RealtimeSidebandHandler {
  suspend fun createCall(request: RealtimeCallRequest): ServerResponse

  suspend fun disconnect(request: SidebandDisconnectRequest): ServerResponse
}

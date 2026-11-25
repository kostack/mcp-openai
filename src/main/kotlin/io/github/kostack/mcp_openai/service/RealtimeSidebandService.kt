package io.github.kostack.mcp_openai.service

import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest

interface RealtimeSidebandService {
  fun connect(request: SidebandConnectRequest)

  suspend fun disconnect(request: SidebandDisconnectRequest)
}

package io.github.kostack.mcp_openai.health

interface SidebandStatusPublisher {
  suspend fun publish(
    callId: String,
    channel: String,
    status: SidebandStatus
  )
}

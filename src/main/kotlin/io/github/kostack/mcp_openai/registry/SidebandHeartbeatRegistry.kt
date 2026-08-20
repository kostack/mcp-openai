package io.github.kostack.mcp_openai.registry

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class SidebandHeartbeatRegistry {
  private val lastPongAt = ConcurrentHashMap<String, Instant>()

  fun initialize(callId: String) {
    lastPongAt[callId] = Instant.now()
  }

  fun pong(callId: String) {
    lastPongAt[callId] = Instant.now()
  }

  fun age(callId: String): Duration? {
    val lastPong = lastPongAt[callId] ?: return null

    return Duration.between(
      lastPong,
      Instant.now()
    )
  }

  fun remove(callId: String) {
    lastPongAt.remove(callId)
  }
}

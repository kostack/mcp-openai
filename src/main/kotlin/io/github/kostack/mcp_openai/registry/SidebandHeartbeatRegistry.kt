package io.github.kostack.mcp_openai.registry

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class SidebandHeartbeatRegistry {
  private val outstandingPingAt = ConcurrentHashMap<String, Instant>()

  fun initialize(callId: String) {
    outstandingPingAt.remove(callId)
  }

  fun ping(callId: String) {
    outstandingPingAt.putIfAbsent(callId, Instant.now())
  }

  fun pong(callId: String) {
    outstandingPingAt.remove(callId)
  }

  fun outstandingPingAge(callId: String): Duration? {
    val pingAt = outstandingPingAt[callId] ?: return null

    return Duration.between(
      pingAt,
      Instant.now()
    )
  }

  fun remove(callId: String) {
    outstandingPingAt.remove(callId)
  }
}

package io.github.kostack.mcp_openai.registry

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

class SidebandSessionRegistry {
  private val sessions = ConcurrentHashMap<String, Job>()

  fun putIfAbsent(
    callId: String,
    job: Job
  ): Job? = sessions.putIfAbsent(callId, job)

  fun cancel(callId: String) {
    sessions.remove(callId)?.cancel()
  }

  fun remove(
    callId: String,
    job: Job
  ) {
    sessions.remove(callId, job)
  }

  fun contains(callId: String): Boolean = sessions.containsKey(callId)
}

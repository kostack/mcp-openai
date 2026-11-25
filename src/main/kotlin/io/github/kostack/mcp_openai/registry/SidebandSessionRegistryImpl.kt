package io.github.kostack.mcp_openai.registry

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

class SidebandSessionRegistryImpl : SidebandSessionRegistry {
  private val sessions = ConcurrentHashMap<String, Job>()

  override fun putIfAbsent(
    callId: String,
    job: Job
  ): Job? = sessions.putIfAbsent(callId, job)

  override fun cancel(callId: String) {
    sessions.remove(callId)?.cancel()
  }

  override fun remove(
    callId: String,
    job: Job
  ) {
    sessions.remove(callId, job)
  }

  override fun contains(callId: String): Boolean = sessions.containsKey(callId)

  override fun get(callId: String): Job? = sessions[callId]
}

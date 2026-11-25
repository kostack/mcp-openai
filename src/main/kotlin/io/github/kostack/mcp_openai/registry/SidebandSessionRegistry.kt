package io.github.kostack.mcp_openai.registry

import kotlinx.coroutines.Job

interface SidebandSessionRegistry {
  fun putIfAbsent(
    callId: String,
    job: Job
  ): Job?

  fun cancel(callId: String)

  fun remove(
    callId: String,
    job: Job
  )

  fun contains(callId: String): Boolean

  fun get(callId: String): Job?
}

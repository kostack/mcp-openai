package io.github.kostack.mcp_openai.health

import io.github.kostack.mcp_openai.registry.SidebandHeartbeatRegistry
import io.github.kostack.mcp_openai.registry.SidebandSessionRegistry
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

class SidebandLivenessService(
  private val sidebandSessionRegistry: SidebandSessionRegistry,
  private val webSocketSessionRegistry: WebSocketSessionRegistry,
  private val heartbeatRegistry: SidebandHeartbeatRegistry,
  private val statusPublisher: SidebandStatusPublisher,
  private val pingInterval: Duration = PING_INTERVAL
) {
  private val scope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val monitors =
    ConcurrentHashMap<String, Job>()

  private val statuses =
    ConcurrentHashMap<String, SidebandStatus>()

  fun start(
    callId: String,
    channel: String
  ) {
    val job =
      scope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
          try {
            check(callId, channel)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            log.error("Heartbeat check failed callId={}", callId, e)
          }
          delay(pingInterval.toMillis().milliseconds)
        }
      }

    val previous =
      monitors.putIfAbsent(callId, job)

    if (previous != null) {
      job.cancel()
      return
    }

    heartbeatRegistry.initialize(callId)
    job.start()
  }

  fun stop(callId: String) {
    monitors
      .remove(callId)
      ?.cancel()

    statuses.remove(callId)
    heartbeatRegistry.remove(callId)
  }

  private suspend fun check(
    callId: String,
    channel: String
  ) {
    val pingSent = webSocketSessionRegistry.sendPing(callId)

    if (!pingSent) {
      updateStatus(
        callId = callId,
        channel = channel,
        status = SidebandStatus.DISCONNECTED
      )

      stop(callId)
      return
    }

    val status = determineStatus(callId)

    log.debug("Sent ping status={}", status)

    updateStatus(
      callId = callId,
      channel = channel,
      status = status
    )
  }

  private fun determineStatus(callId: String): SidebandStatus {
    if (!sidebandSessionRegistry.isActive(callId)) {
      return SidebandStatus.DISCONNECTED
    }

    if (!webSocketSessionRegistry.isOpen(callId)) {
      return SidebandStatus.DISCONNECTED
    }

    val pongAge = heartbeatRegistry.age(callId) ?: return SidebandStatus.DEGRADED

    return when {
      pongAge >= DISCONNECTED_TIMEOUT -> {
        SidebandStatus.DISCONNECTED
      }

      pongAge >= DEGRADED_TIMEOUT -> {
        SidebandStatus.DEGRADED
      }

      else -> {
        SidebandStatus.CONNECTED
      }
    }
  }

  private suspend fun updateStatus(
    callId: String,
    channel: String,
    status: SidebandStatus
  ) {
    val previous = statuses.put(callId, status)

    if (previous == status) {
      return
    }

    statusPublisher.publish(
      callId = callId,
      channel = channel,
      status = status
    )
  }

  companion object {
    private val PING_INTERVAL =
      Duration.ofSeconds(10)

    private val DEGRADED_TIMEOUT =
      Duration.ofSeconds(25)

    private val DISCONNECTED_TIMEOUT =
      Duration.ofSeconds(45)

    private val log = LoggerFactory.getLogger(SidebandLivenessService::class.java)
  }
}

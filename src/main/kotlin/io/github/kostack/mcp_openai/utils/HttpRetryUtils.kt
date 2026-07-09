package io.github.kostack.mcp_openai.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.PrematureCloseException
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object HttpRetryUtils {
  private val log = LoggerFactory.getLogger(HttpRetryUtils::class.java)

  suspend fun <T> retryHttpCall(
    initialBackoff: Duration = Duration.ofSeconds(1),
    defaultRateLimitDelay: Duration = Duration.ofSeconds(3),
    maxBackoff: Duration = Duration.ofSeconds(10),
    maxRetries: Long = 3,
    backoffMultiplier: Double = 2.0,
    block: suspend () -> T
  ): T {
    require(maxRetries >= 0) { "maxRetries must be >= 0" }
    require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0" }

    var retries = 0L
    var currentBackoffMillis = initialBackoff.toMillis()
    val maxBackoffMillis = maxBackoff.toMillis()

    while (true) {
      try {
        return block()
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        if (!error.isRetryableHttpFailure()) {
          log.error("Non-retryable error [{}]: {}", error::class.simpleName, error.message, error)
          throw error
        }

        if (retries >= maxRetries) {
          log.warn("Retries exhausted ({} retries)", maxRetries)
          throw error
        }

        val delayDuration =
          when {
            error.isTooManyRequests() -> defaultRateLimitDelay
            else -> Duration.ofMillis(currentBackoffMillis)
          }

        log.warn(
          "Retry attempt {} after {}s due to {}: {}",
          retries + 1,
          delayDuration.seconds,
          error::class.simpleName,
          error.message
        )

        delay(delayDuration.toMillis().milliseconds)

        if (!error.isTooManyRequests()) {
          currentBackoffMillis =
            (currentBackoffMillis * backoffMultiplier)
              .toLong()
              .coerceAtMost(maxBackoffMillis)
        }

        retries++
      }
    }
  }

  private fun Throwable.isTooManyRequests(): Boolean =
    this is WebClientResponseException && statusCode == HttpStatus.TOO_MANY_REQUESTS

  private fun Throwable.isRetryableHttpFailure(): Boolean =
    isTooManyRequests() ||
      isRetryableHttpStatus() ||
      isRequestTransportFailure() ||
      isConnectionReset() ||
      isPrematureClose()

  private fun Throwable.isRetryableHttpStatus(): Boolean =
    this is WebClientResponseException &&
      statusCode in
      setOf(
        HttpStatus.TOO_MANY_REQUESTS,
        HttpStatus.BAD_GATEWAY,
        HttpStatus.SERVICE_UNAVAILABLE,
        HttpStatus.GATEWAY_TIMEOUT
      )

  private fun Throwable.isRequestTransportFailure(): Boolean = this is WebClientRequestException

  private fun Throwable.isPrematureClose(): Boolean =
    this is PrematureCloseException ||
      anyCause { it is PrematureCloseException }

  private fun Throwable.isConnectionReset(): Boolean =
    anyCause {
      it.message?.contains("Connection reset by peer", ignoreCase = true) == true ||
        it.message?.contains("error(-104)", ignoreCase = true) == true ||
        it.message?.contains("io_uring read", ignoreCase = true) == true
    }

  private fun Throwable.anyCause(predicate: (Throwable) -> Boolean): Boolean {
    var current: Throwable? = this

    while (current != null) {
      if (predicate(current)) return true
      current = current.cause
    }

    return false
  }
}

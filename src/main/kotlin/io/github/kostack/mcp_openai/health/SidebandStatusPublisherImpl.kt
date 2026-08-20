package io.github.kostack.mcp_openai.health

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SidebandStatusPublisherImpl : SidebandStatusPublisher {
  override suspend fun publish(
    callId: String,
    channel: String,
    status: SidebandStatus
  ) {
    logger.info("publish: callId={}, channel={}, status={}", callId, channel, status)
  }

  companion object {
    private val logger: Logger = LoggerFactory.getLogger(SidebandStatusPublisherImpl::class.java)
  }
}

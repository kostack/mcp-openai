package io.github.kostack.mcp_openai.tool

import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class AbstractTool : Tool {
  val log: Logger = LoggerFactory.getLogger(this::class.java)
  abstract val namespace: String
  abstract val toolName: String
  protected abstract val description: String
}

package io.github.kostack.mcp_openai

import io.github.kostack.mcp_openai.configuration.McpProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@EnableConfigurationProperties(McpProperties::class)
@SpringBootApplication
class TestApplication

fun main(args: Array<String>) {
  runApplication<TestApplication>(*args)
}

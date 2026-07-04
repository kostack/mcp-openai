package io.github.kostack.mcp_openai

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TestApplication

fun main(args: Array<String>) {
  runApplication<TestApplication>(*args)
}

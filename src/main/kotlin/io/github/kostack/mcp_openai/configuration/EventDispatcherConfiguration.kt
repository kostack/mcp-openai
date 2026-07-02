package io.github.kostack.mcp_openai.configuration

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(McpProperties::class)
@ComponentScan(basePackages = ["io.github.kostack.event_dispatcher"])
internal class EventDispatcherConfiguration

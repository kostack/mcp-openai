package io.github.kostack.mcp_openai.autoconfiguration

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.RealtimeSidebandHandler
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest
import io.github.kostack.mcp_openai.dto.TokenRequest
import io.github.kostack.mcp_openai.listener.ConversationListener
import io.github.kostack.mcp_openai.registry.SidebandSessionRegistry
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import io.github.kostack.mcp_openai.service.ConversationStore
import io.github.kostack.mcp_openai.service.OpenAiHttpService
import io.github.kostack.mcp_openai.service.RealtimeEventHandler
import io.github.kostack.mcp_openai.service.RealtimeSidebandService
import io.github.kostack.mcp_openai.tool.Tool
import io.github.kostack.mcp_openai.tool.ToolDispatcher
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.server.CoRouterFunctionDsl
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.reactive.socket.client.WebSocketClient
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.WebsocketClientSpec
import tools.jackson.databind.ObjectMapper

@AutoConfiguration
@AutoConfigureBefore(name = ["io.github.kostack.event_dispatcher.SuspendDispatcherConfiguration"])
@ConditionalOnClass(WebClient::class, CoRouterFunctionDsl::class)
@EnableConfigurationProperties(McpProperties::class)
class McpOpenAiAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  fun mcpOpenAiWebClient(builder: ObjectProvider<WebClient.Builder>): WebClient =
    builder.ifAvailable?.build() ?: WebClient.create()

  @Bean
  @ConditionalOnMissingBean(name = ["realtimeSidebandWebSocketClient"])
  fun realtimeSidebandWebSocketClient(mcpProperties: McpProperties): WebSocketClient =
    ReactorNettyWebSocketClient(
      HttpClient.create()
    ) {
      WebsocketClientSpec
        .builder()
        .maxFramePayloadLength(mcpProperties.sidebandMaxFramePayloadLength)
    }

  @Bean
  @ConditionalOnMissingBean(name = ["sidebandSessionRegistry"])
  fun sidebandSessionRegistry(): SidebandSessionRegistry = SidebandSessionRegistry()

  @Bean
  @ConditionalOnMissingBean(name = ["webSocketSessionRegistry"])
  fun webSocketSessionRegistry(objectMapper: ObjectMapper): WebSocketSessionRegistry =
    WebSocketSessionRegistry(objectMapper)

  @Bean
  @ConditionalOnMissingBean(name = ["conversationStore"])
  fun conversationStore(): ConversationStore = ConversationStore()

  @Bean
  @ConditionalOnMissingBean(name = ["toolDispatcher"])
  fun toolDispatcher(tools: List<Tool>): ToolDispatcher = ToolDispatcher(tools)

  @Bean
  @ConditionalOnMissingBean(name = ["openAiHttpService"])
  fun openAiHttpService(
    webClient: WebClient,
    mcpProperties: McpProperties
  ): OpenAiHttpService = OpenAiHttpService(webClient, mcpProperties)

  @Bean
  @ConditionalOnMissingBean(name = ["realtimeEventHandler"])
  fun realtimeEventHandler(
    objectMapper: ObjectMapper,
    toolDispatcher: ToolDispatcher,
    webSocketSessionRegistry: WebSocketSessionRegistry,
    suspendDispatcher: SuspendDispatcher
  ): RealtimeEventHandler =
    RealtimeEventHandler(
      objectMapper,
      toolDispatcher,
      webSocketSessionRegistry,
      suspendDispatcher
    )

  @Bean
  @ConditionalOnMissingBean(name = ["realtimeSidebandService"])
  fun realtimeSidebandService(
    mcpProperties: McpProperties,
    objectMapper: ObjectMapper,
    sidebandRegistry: SidebandSessionRegistry,
    sessionRegistry: WebSocketSessionRegistry,
    realtimeEventHandler: RealtimeEventHandler,
    suspendDispatcher: SuspendDispatcher,
    @Qualifier("realtimeSidebandWebSocketClient") sidebandWebSocketClient: WebSocketClient
  ): RealtimeSidebandService =
    RealtimeSidebandService(
      mcpProperties,
      objectMapper,
      sidebandRegistry,
      sessionRegistry,
      realtimeEventHandler,
      suspendDispatcher,
      sidebandWebSocketClient
    )

  @Bean
  @ConditionalOnMissingBean(name = ["realtimeSidebandHandler"])
  fun realtimeSidebandHandler(
    sidebandService: RealtimeSidebandService,
    openAiHttpService: OpenAiHttpService,
    suspendDispatcher: SuspendDispatcher,
    toolDispatcher: ToolDispatcher,
    properties: McpProperties
  ): RealtimeSidebandHandler =
    RealtimeSidebandHandler(
      sidebandService,
      openAiHttpService,
      suspendDispatcher,
      toolDispatcher,
      properties
    )

  @Bean
  @ConditionalOnMissingBean(name = ["conversationListener"])
  fun conversationListener(conversationStore: ConversationStore): ConversationListener =
    ConversationListener(conversationStore)

  @Bean
  @ConditionalOnMissingBean(name = ["realtimeSidebandRoutes"])
  fun realtimeSidebandRoutes(
    handler: RealtimeSidebandHandler,
    properties: McpProperties
  ) = coRouter {
    POST("${properties.sidebandPrefix}/token") { request ->
      handler.createToken(request.awaitBody<TokenRequest>())
    }

    POST("${properties.sidebandPrefix}/connect") { request ->
      handler.connect(request.awaitBody<SidebandConnectRequest>())
    }

    POST("${properties.sidebandPrefix}/disconnect") { request ->
      handler.disconnect(request.awaitBody<SidebandDisconnectRequest>())
    }
  }
}

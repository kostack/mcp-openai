package io.github.kostack.mcp_openai.autoconfiguration

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.RealtimeSidebandHandler
import io.github.kostack.mcp_openai.RealtimeSidebandHandlerImpl
import io.github.kostack.mcp_openai.RealtimeSidebandRoutes
import io.github.kostack.mcp_openai.RealtimeSidebandRoutesImpl
import io.github.kostack.mcp_openai.dto.RealtimeCallRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest
import io.github.kostack.mcp_openai.listener.ConversationListener
import io.github.kostack.mcp_openai.listener.ConversationListenerImpl
import io.github.kostack.mcp_openai.registry.SidebandSessionRegistry
import io.github.kostack.mcp_openai.registry.SidebandSessionRegistryImpl
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistryImpl
import io.github.kostack.mcp_openai.service.ConversationStore
import io.github.kostack.mcp_openai.service.ConversationStoreImpl
import io.github.kostack.mcp_openai.service.OpenAiHttpService
import io.github.kostack.mcp_openai.service.OpenAiHttpServiceImpl
import io.github.kostack.mcp_openai.service.RealtimeEventHandler
import io.github.kostack.mcp_openai.service.RealtimeEventHandlerImpl
import io.github.kostack.mcp_openai.service.RealtimeSidebandService
import io.github.kostack.mcp_openai.service.RealtimeSidebandServiceImpl
import io.github.kostack.mcp_openai.tool.Tool
import io.github.kostack.mcp_openai.tool.ToolDispatcher
import io.github.kostack.mcp_openai.tool.ToolDispatcherImpl
import org.springframework.beans.factory.ObjectProvider
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
  @ConditionalOnMissingBean(WebSocketClient::class)
  fun realtimeSidebandWebSocketClient(mcpProperties: McpProperties): WebSocketClient =
    ReactorNettyWebSocketClient(
      HttpClient.create()
    ) {
      WebsocketClientSpec
        .builder()
        .maxFramePayloadLength(mcpProperties.sidebandMaxFramePayloadLength)
    }

  @Bean
  @ConditionalOnMissingBean
  fun sidebandSessionRegistry(): SidebandSessionRegistry = SidebandSessionRegistryImpl()

  @Bean
  @ConditionalOnMissingBean
  fun webSocketSessionRegistry(
    objectMapper: ObjectMapper,
    properties: McpProperties
  ): WebSocketSessionRegistry = WebSocketSessionRegistryImpl(objectMapper, properties.outboundBufferCapacity)

  @Bean
  @ConditionalOnMissingBean
  fun conversationStore(): ConversationStore = ConversationStoreImpl()

  @Bean
  @ConditionalOnMissingBean
  fun toolDispatcher(tools: List<Tool>): ToolDispatcher = ToolDispatcherImpl(tools)

  @Bean
  @ConditionalOnMissingBean
  fun openAiHttpService(
    webClient: WebClient,
    mcpProperties: McpProperties
  ): OpenAiHttpService = OpenAiHttpServiceImpl(webClient, mcpProperties)

  @Bean
  @ConditionalOnMissingBean
  fun realtimeEventHandler(
    objectMapper: ObjectMapper,
    toolDispatcher: ToolDispatcher,
    webSocketSessionRegistry: WebSocketSessionRegistry,
    suspendDispatcher: SuspendDispatcher,
    properties: McpProperties
  ): RealtimeEventHandler =
    RealtimeEventHandlerImpl(
      objectMapper,
      toolDispatcher,
      webSocketSessionRegistry,
      suspendDispatcher,
      maxConcurrentCalls = properties.toolMaxConcurrentCalls,
      toolTimeoutMillis = properties.toolTimeoutMillis
    )

  @Bean
  @ConditionalOnMissingBean
  fun realtimeSidebandService(
    mcpProperties: McpProperties,
    objectMapper: ObjectMapper,
    sidebandRegistry: SidebandSessionRegistry,
    sessionRegistry: WebSocketSessionRegistry,
    realtimeEventHandler: RealtimeEventHandler,
    suspendDispatcher: SuspendDispatcher,
    openAiHttpService: OpenAiHttpService,
    sidebandWebSocketClient: WebSocketClient
  ): RealtimeSidebandService =
    RealtimeSidebandServiceImpl(
      mcpProperties,
      objectMapper,
      sidebandRegistry,
      sessionRegistry,
      realtimeEventHandler,
      suspendDispatcher,
      sidebandWebSocketClient,
      openAiHttpService
    )

  @Bean
  @ConditionalOnMissingBean
  fun realtimeSidebandHandler(
    sidebandService: RealtimeSidebandService,
    openAiHttpService: OpenAiHttpService,
    suspendDispatcher: SuspendDispatcher,
    toolDispatcher: ToolDispatcher,
    properties: McpProperties
  ): RealtimeSidebandHandler =
    RealtimeSidebandHandlerImpl(
      sidebandService,
      openAiHttpService,
      suspendDispatcher,
      toolDispatcher,
      properties
    )

  @Bean
  @ConditionalOnMissingBean
  fun conversationListener(conversationStore: ConversationStore): ConversationListener =
    ConversationListenerImpl(conversationStore)

  @Bean
  @ConditionalOnMissingBean(RealtimeSidebandRoutes::class)
  fun realtimeSidebandRoutes(
    handler: RealtimeSidebandHandler,
    properties: McpProperties
  ): RealtimeSidebandRoutes =
    RealtimeSidebandRoutesImpl(
      coRouter {
        POST("${properties.sidebandPrefix}/calls") { request ->
          handler.createCall(request.awaitBody<RealtimeCallRequest>())
        }

        POST("${properties.sidebandPrefix}/disconnect") { request ->
          handler.disconnect(request.awaitBody<SidebandDisconnectRequest>())
        }
      }
    )
}

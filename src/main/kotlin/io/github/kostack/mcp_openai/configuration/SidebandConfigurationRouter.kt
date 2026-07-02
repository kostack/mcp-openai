package io.github.kostack.mcp_openai.configuration

import io.github.kostack.mcp_openai.RealtimeSidebandHandler
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest
import io.github.kostack.mcp_openai.dto.TokenRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.coRouter

@Configuration
@ComponentScan(basePackages = ["io.github.kostack.mcp_openai"])
class SidebandConfigurationRouter(
  private val properties: McpProperties
) {
  @Bean
  fun realtimeSidebandRoutes(handler: RealtimeSidebandHandler) =
    coRouter {
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

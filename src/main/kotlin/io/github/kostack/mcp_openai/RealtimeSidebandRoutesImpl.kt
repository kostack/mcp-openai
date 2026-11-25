package io.github.kostack.mcp_openai

import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

class RealtimeSidebandRoutesImpl(
  router: RouterFunction<ServerResponse>
) : RealtimeSidebandRoutes,
  RouterFunction<ServerResponse> by router

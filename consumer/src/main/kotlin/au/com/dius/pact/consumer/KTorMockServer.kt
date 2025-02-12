package au.com.dius.pact.consumer

import au.com.dius.pact.consumer.Headers.headerToString
import au.com.dius.pact.consumer.model.MockHttpsProviderConfig
import au.com.dius.pact.consumer.model.MockProviderConfig
import au.com.dius.pact.core.model.BasePact
import au.com.dius.pact.core.model.ContentType
import au.com.dius.pact.core.model.IResponse
import au.com.dius.pact.core.model.OptionalBody
import au.com.dius.pact.core.model.Pact
import au.com.dius.pact.core.model.Request
import au.com.dius.pact.core.model.Response
import au.com.dius.pact.core.support.Result
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.util.network.hostname
import io.ktor.util.network.port
import io.netty.channel.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.oshai.kotlinlogging.KLogging
import io.ktor.http.parseHeaderValue
import java.net.SocketAddress
import java.util.zip.DeflaterInputStream
import java.util.zip.GZIPInputStream

class KTorMockServer @JvmOverloads constructor(
  pact: BasePact,
  config: MockProviderConfig,
  private val stopTimeout: Long = 20000
) : BaseMockServer(pact, config) {

  private val env = applicationEngineEnvironment {
    if (config is MockHttpsProviderConfig) {
      sslConnector(keyStore = config.keyStore!!, keyAlias = config.keyStoreAlias,
        keyStorePassword = { config.keystorePassword.toCharArray() },
        privateKeyPassword = { config.privateKeyPassword.toCharArray() }) {
        host = config.hostname
        port = config.port
      }
    } else {
      connector {
        host = config.hostname
        port = config.port
      }
    }

    module {
      install(CallLogging)
      intercept(ApplicationCallPipeline.Call) {
        if (context.request.httpMethod == HttpMethod.Options && context.request.headers.contains("X-PACT-BOOTCHECK")) {
          context.response.header("X-PACT-BOOTCHECK", "true")
          context.respond(HttpStatusCode.OK)
        } else {
          try {
            val request = toPactRequest(context)
            logger.debug { "Received request: $request" }
            val response = generatePactResponse(request)
            logger.debug { "Generating response: $response" }
            pactResponseToKTorResponse(response, context)
          } catch (e: Exception) {
            logger.error(e) { "Failed to generate response" }
            pactResponseToKTorResponse(Response(500, mutableMapOf("Content-Type" to listOf("application/json")),
              OptionalBody.body("{\"error\": ${e.message}}".toByteArray(), ContentType.JSON)), context)
          }
        }
      }
    }
  }

  private var server: NettyApplicationEngine = embeddedServer(Netty, environment = env, configure = {})

  private suspend fun pactResponseToKTorResponse(response: IResponse, call: ApplicationCall) {
    response.headers.forEach { entry ->
      entry.value.forEach {
        call.response.headers.append(entry.key, it, safeOnly = false)
      }
    }

    val body = response.body
    if (body.isPresent()) {
      call.respondBytes(status = HttpStatusCode.fromValue(response.status), bytes = body.unwrap())
    } else {
      call.respond(HttpStatusCode.fromValue(response.status))
    }
  }

  private suspend fun toPactRequest(call: ApplicationCall): Request {
    val request = call.request
    val headers = request.headers.entries().associate { entry ->
      if (entry.value.size == 1 && Headers.isKnowMultiValueHeader(entry.key)) {
        entry.key to parseHeaderValue(entry.value[0]).map { headerToString(it) }
      } else {
        entry.key to entry.value
      }
    }
    val bodyContents = withContext(Dispatchers.IO) {
      val stream = call.receiveStream()
      when (bodyIsCompressed(request.headers["Content-Encoding"])) {
        "gzip" -> GZIPInputStream(stream).readBytes()
        "deflate" -> DeflaterInputStream(stream).readBytes()
        else -> stream.readBytes()
      }
    }
    val body = if (bodyContents.isEmpty()) {
      OptionalBody.empty()
    } else {
      OptionalBody.body(bodyContents, ContentType.fromString(request.headers["Content-Type"]).or(ContentType.JSON))
    }
    return Request(request.httpMethod.value, request.path(),
      request.queryParameters.entries().associate { it.toPair() }.toMutableMap(),
      headers.toMutableMap(), body)
  }

  override fun getUrl(): String {
    val address = socketAddress()
    return if (address != null) {
      // Stupid GitHub Windows agents
      val host = if (address.hostname.lowercase() == "miningmadness.com") {
        config.hostname
      } else {
        address.hostname
      }
      "${config.scheme}://$host:${address.port}"
    } else {
      val connectorConfig = server.environment.connectors.first()
      "${config.scheme}://${connectorConfig.host}:${connectorConfig.port}"
    }
  }

  private fun socketAddress(): SocketAddress? {
    val field = server.javaClass.getDeclaredField("channels")
    field.isAccessible = true
    val channels = field.get(server) as List<Channel>?
    return channels?.first()?.localAddress()
  }

  override fun getPort() = socketAddress()?.port ?: server.environment.connectors.first().port

  override fun updatePact(pact: Pact): Pact {
    return if (pact.isV4Pact()) {
      when (val p = pact.asV4Pact()) {
        is Result.Ok -> {
          for (interaction in p.value.interactions) {
            interaction.asV4Interaction().transport = if (config is MockHttpsProviderConfig) "https" else "http"
          }
          p.value
        }
        is Result.Err -> pact
      }
    } else {
      pact
    }
  }

  override fun start() {
    logger.debug { "Starting mock server" }
    server.start()
    logger.debug { "Mock server started: ${server.environment.connectors}" }
  }

  override fun stop() {
    server.stop(100, stopTimeout)
    logger.debug { "Mock server shutdown" }
  }

  companion object : KLogging()
}

package kamon
package instrumentation
package http

import java.time.Duration

import com.typesafe.config.Config
import kamon.context.Context
import kamon.instrumentation.http.HttpServer.Settings.TagMode
import kamon.instrumentation.tag.TagKeys
import kamon.metric.InstrumentGroup
import kamon.metric.MeasurementUnit.information
import kamon.tag.Lookups.option
import kamon.tag.TagSet
import kamon.trace.Span
import kamon.util.Filter
import org.slf4j.LoggerFactory


/**
  * HTTP Server instrumentation handler that takes care of context propagation, distributed tracing and HTTP server
  * metrics. Instances can be created by using the `HttpServer.of` method with the desired configuration name. All
  * configuration for the default HTTP instrumentation is at "kamon.instrumentation.http-server.default".
  *
  * The default implementation shipping with Kamon provides:
  *
  *   * Context Propagation: Incoming and Returning context propagation as well as incoming context tags. Context
  *     propagation is further used to enable distributed tracing on top of any instrumented HTTP Server.
  *
  *   * Distributed Tracing: Automatically join traces initiated by the callers of this service and apply span and
  *     metric tags from the incoming requests as well as form the incoming context tags.
  *
  *   * Server Metrics: Basic request processing metrics to understand connection usage, throughput and response code
  *     counts in the HTTP server.
  *
  */
trait HttpServer {

  /**
    * Initiate handling of a HTTP request received by this server. The returned RequestHandler contains the Span that
    * represents the processing of the incoming HTTP request (if tracing is enabled) and the Context extracted from
    * HTTP headers (if context propagation is enabled).
    *
    * Callers of this method **must** always ensure that the doneReceiving, send and doneSending callbacks are invoked
    * for all incoming requests.
    *
    */
  def receive(request: HttpMessage.Request): HttpServer.RequestHandler

  /**
    * Signals that a new HTTP connection has been opened.
    */
  def openConnection(): Unit

  /**
    * Signals that a HTTP connection has been closed.
    *
    */
  def closeConnection(): Unit =
    closeConnection(Duration.ZERO, -1L)

  /**
    * Signals that a HTTP connection has been closed and reports for how long was that connection open.
    *
    */
  def closeConnection(lifetime: Duration): Unit =
    closeConnection(lifetime, -1L)

  /**
    * Signals that a HTTP connection has been closed and reports how many requests were handled by that connection.
    */
  def closeConnection(handledRequests: Long): Unit =
    closeConnection(Duration.ZERO, handledRequests)

  /**
    * Signals that a HTTP connection has been closed and reports for how long was that connection open and how many
    * requests were handled through it.
    */
  def closeConnection(lifetime: Duration, handledRequests: Long): Unit

  /**
    * Frees resources that might have been acquired to provide the instrumentation. Behavior on HttpServer instances
    * after calling this function is undefined.
    */
  def shutdown(): Unit

}

object HttpServer {

  /**
    * Handler associated to the processing of a single request. The instrumentation code using this class is responsible
    * of creating a dedicated `HttpServer.RequestHandler` instance for each received request should invoking the
    * doneReceiving, send and doneSending callbacks when appropriate.
    */
  trait RequestHandler {

    /**
      * If context propagation is enabled this function returns the incoming context associated wih this request,
      * otherwise `Context.Empty` is returned.
      */
    def context: Context

    /**
      * Span representing the current HTTP server operation. If tracing is disabled this will return an empty span.
      */
    def span: Span

    /**
      * Signals that the entire request (headers and body) has been received.
      */
    def doneReceiving(): Unit =
      doneReceiving(-1L)

    /**
      * Signals that the entire request (headers and body) has been received and records the size of the received
      * payload.
      */
    def doneReceiving(receivedBytes: Long): Unit

    /**
      * Process a response to be sent back to the client. Since returning keys might need to included in the response
      * headers, users of this class must ensure that the returned HttpResponse is used instead of the original one
      * passed into this function.
      *
      * @param response Wraps the HTTP response to be sent back to the client.
      * @param context Context that should be used for writing returning keys into the response.
      * @return The modified HTTP response that should be sent to clients.
      */
    def send[HttpResponse](response: HttpMessage.ResponseBuilder[HttpResponse], context: Context): HttpResponse

    /**
      * Signals that the entire response (headers and body) has been sent to the client.
      */
    def doneSending(): Unit =
      doneSending(-1L)

    /**
      * Signals that the entire response (headers and body) has been sent to the client and records its size, if
      * available.
      */
    def doneSending(sentBytes: Long): Unit

  }

  /**
    * Holds all metric instruments required to track the behavior of an HTTP server.
    */
  class Metrics private(commonTags: TagSet) extends InstrumentGroup(commonTags) {
    import Metrics._

    val requestsInformational = register(CompletedRequests, TagKeys.HttpStatusCode, "1xx")
    val requestsSuccessful = register(CompletedRequests, TagKeys.HttpStatusCode, "2xx")
    val requestsRedirection = register(CompletedRequests, TagKeys.HttpStatusCode, "3xx")
    val requestsClientError = register(CompletedRequests, TagKeys.HttpStatusCode, "4xx")
    val requestsServerError = register(CompletedRequests, TagKeys.HttpStatusCode, "5xx")

    val activeRequests = register(ActiveRequests)
    val requestSize = register(RequestSize)
    val responseSize = register(ResponseSize)
    val connectionLifetime = register(ConnectionLifetime)
    val connectionUsage = register(ConnectionUsage)
    val openConnections = register(OpenConnections)


    def countCompletedRequest(statusCode: Int): Unit = {
      if(statusCode >= 200 && statusCode <= 299)
        requestsSuccessful.increment()
      else if(statusCode >= 500 && statusCode <= 599)
        requestsServerError.increment()
      else if(statusCode >= 400 && statusCode <= 499)
        requestsClientError.increment()
      else if(statusCode >= 300 && statusCode <= 399)
        requestsRedirection.increment()
      else if(statusCode >= 100 && statusCode <= 199)
        requestsInformational.increment()
      else {
        _logger.warn("Unknown HTTP status code {} found when recording HTTP server metrics", statusCode.toString)
      }
    }
  }


  object Metrics {

    private val _logger = LoggerFactory.getLogger(classOf[HttpServer.Metrics])

    val CompletedRequests = Kamon.counter(
      name = "http.server.requests",
      description = "Number of completed requests per status code")

    val ActiveRequests = Kamon.rangeSampler(
      name = "http.server.request.active",
      description = "Number of requests being processed simultaneously at any point in time")

    val RequestSize = Kamon.histogram(
      name = "http.server.request.size",
      description = "Request size distribution (including headers and body) for all requests received by the server",
      unit = information.bytes)

    val ResponseSize = Kamon.histogram(
      name = "http.server.response.size",
      description = "Response size distribution (including headers and body) for all responses served by the server",
      unit = information.bytes)

    val ConnectionLifetime = Kamon.timer(
      name = "http.server.connection.lifetime",
      description = "Tracks the time elapsed between connection creation and connection close")

    val ConnectionUsage = Kamon.histogram(
      name = "http.server.connection.usage",
      description = "Distribution of number of requests handled per connection during their entire lifetime")

    val OpenConnections = Kamon.rangeSampler(
      name = "http.server.connection.open",
      description = "Number of open connections")


    /**
      * Creates a new HttpServer.Metrics instance with the provided component, interface and port tags.
      */
    def of(component: String, interface: String, port: Int): Metrics =
      new HttpServer.Metrics(
        TagSet.builder()
          .add(TagKeys.Component, component)
          .add(TagKeys.Interface, interface)
          .add(TagKeys.Port, port)
          .build()
      )
  }

  def of(name: String, component: String, interface: String, port: Int): HttpServer = {
    of(name, component, interface, port, Kamon, Kamon)
  }

  def of(name: String, component: String, interface: String, port: Int, configuration: Configuration,
      contextPropagation: ContextPropagation): HttpServer = {

    val defaultConfiguration = configuration.config().getConfig(DefaultHttpServerConfiguration)
    val configWithFallback = if(name == DefaultHttpServer) defaultConfiguration else {
      configuration.config().getConfig(HttpServerConfigurationPrefix + "." + name).withFallback(defaultConfiguration)
    }

    new HttpServer.Default(Settings.from(configWithFallback), contextPropagation, component, interface, port)
  }

  val HttpServerConfigurationPrefix = "kamon.instrumentation.http-server"
  val DefaultHttpServer = "default"
  val DefaultHttpServerConfiguration = s"$HttpServerConfigurationPrefix.default"


  private class Default(settings: Settings, contextPropagation: ContextPropagation, component: String, interface: String, port: Int)
      extends HttpServer {

    private val _metrics = if(settings.enableServerMetrics) Some(HttpServer.Metrics.of(component, interface, port)) else None
    private val _log = LoggerFactory.getLogger(classOf[Default])
    private val _propagation = contextPropagation.httpPropagation(settings.propagationChannel)
      .getOrElse {
        _log.warn(s"Could not find HTTP propagation [${settings.propagationChannel}], falling back to the default HTTP propagation")
        contextPropagation.defaultHttpPropagation()
      }

    override def receive(request: HttpMessage.Request): RequestHandler = {

      val incomingContext = if(settings.enableContextPropagation)
        _propagation.read(request)
      else Context.Empty

      val requestSpan = if(settings.enableTracing)
        buildServerSpan(incomingContext, request)
      else Span.Empty

      val handlerContext = if(!requestSpan.isEmpty)
        incomingContext.withKey(Span.Key, requestSpan)
      else incomingContext

      _metrics.foreach { httpServerMetrics =>
        httpServerMetrics.activeRequests.increment()
      }

      new HttpServer.RequestHandler {
        override def context: Context =
          handlerContext

        override def span: Span =
          requestSpan

        override def doneReceiving(receivedBytes: Long): Unit = {
          if(receivedBytes >= 0) {
            _metrics.foreach { httpServerMetrics =>
              httpServerMetrics.requestSize.record(receivedBytes)
            }
          }
        }

        override def send[HttpResponse](response: HttpMessage.ResponseBuilder[HttpResponse], context: Context): HttpResponse = {
          def addResponseTag(tag: String, value: String, mode: TagMode): Unit = mode match {
            case TagMode.Metric => span.tagMetric(tag, value)
            case TagMode.Span => span.tag(tag, value)
            case TagMode.Off =>
          }

          _metrics.foreach { httpServerMetrics =>
            httpServerMetrics.countCompletedRequest(response.statusCode)
          }

          if(!span.isEmpty) {
            settings.traceIDResponseHeader.foreach(traceIDHeader => response.write(traceIDHeader, span.trace.id.string))
            settings.spanIDResponseHeader.foreach(spanIDHeader => response.write(spanIDHeader, span.id.string))
          }

          addResponseTag(TagKeys.HttpStatusCode, response.statusCode.toString, settings.statusCodeTagMode)
          response.build()
        }

        override def doneSending(sentBytes: Long): Unit = {
          _metrics.foreach { httpServerMetrics =>
            httpServerMetrics.activeRequests.decrement()

            if(sentBytes >= 0)
              httpServerMetrics.responseSize.record(sentBytes)
          }

          span.finish()
        }
      }
    }

    override def openConnection(): Unit = {
      _metrics.foreach { httpServerMetrics =>
        httpServerMetrics.openConnections.increment()
      }
    }

    override def closeConnection(lifetime: Duration, handledRequests: Long): Unit = {
      _metrics.foreach { httpServerMetrics =>
        httpServerMetrics.openConnections.decrement()

        if(lifetime != Duration.ZERO)
          httpServerMetrics.connectionLifetime.record(lifetime.toNanos)

        if(handledRequests > 0)
          httpServerMetrics.connectionUsage.record(handledRequests)
      }
    }

    override def shutdown(): Unit = {
      _metrics.foreach { httpServerMetrics =>
        httpServerMetrics.remove()
      }
    }


    private def buildServerSpan(context: Context, request: HttpMessage.Request): Span = {
      val span = Kamon.serverSpanBuilder(operationName(request), component)

      if(!settings.enableSpanMetrics)
        span.disableMetrics()


      for { traceIdTag <- settings.traceIDTag; customTraceID <- context.getTag(option(traceIdTag)) } {
        val identifier = Kamon.identifierScheme.traceIdFactory.from(customTraceID)
        if(!identifier.isEmpty)
          span.traceId(identifier)
      }

      def addRequestTag(tag: String, value: String, mode: TagMode): Unit = mode match {
        case TagMode.Metric => span.tagMetric(tag, value)
        case TagMode.Span => span.tag(tag, value)
        case TagMode.Off =>
      }

      addRequestTag(TagKeys.HttpUrl, request.url, settings.urlTagMode)
      addRequestTag(TagKeys.HttpMethod, request.method, settings.urlTagMode)
      settings.contextTags.foreach {
        case (tagName, mode) => context.getTag(option(tagName)).foreach(tagValue => addRequestTag(tagName, tagValue, mode))
      }

      span.start()
    }

    private def operationName(request: HttpMessage.Request): String = {
      val requestPath = request.path
      val customMapping = settings.operationMappings.collectFirst {
        case (pattern, operationName) if pattern.accept(requestPath) => operationName
      }

      customMapping.getOrElse("http.request")
    }
  }


  case class Settings(
    enableContextPropagation: Boolean,
    propagationChannel: String,
    enableServerMetrics: Boolean,
    enableTracing: Boolean,
    traceIDTag: Option[String],
    enableSpanMetrics: Boolean,
    urlTagMode: TagMode,
    methodTagMode: TagMode,
    statusCodeTagMode: TagMode,
    contextTags: Map[String, TagMode],
    traceIDResponseHeader: Option[String],
    spanIDResponseHeader: Option[String],
    unhandledOperationName: String,
    operationMappings: Map[Filter.Glob, String]
  )

  object Settings {

    sealed trait TagMode
    object TagMode {
      case object Metric extends TagMode
      case object Span extends TagMode
      case object Off extends TagMode

      def from(value: String): TagMode = value.toLowerCase match {
        case "metric" => TagMode.Metric
        case "span" => TagMode.Span
        case _ => TagMode.Off
      }
    }

    def from(config: Config): Settings = {
      def optionalString(value: String): Option[String] = if(value.equalsIgnoreCase("none")) None else Some(value)

      // Context propagation settings
      val enablePropagation = config.getBoolean("propagation.enabled")
      val propagationChannel = config.getString("propagation.channel")

      // HTTP Server metrics settings
      val enableServerMetrics = config.getBoolean("metrics.enabled")

      // Tracing settings
      val enableTracing = config.getBoolean("tracing.enabled")
      val traceIdTag = Option(config.getString("tracing.preferred-trace-id-tag")).filterNot(_ == "none")
      val enableSpanMetrics = config.getBoolean("tracing.span-metrics")
      val urlTagMode = TagMode.from(config.getString("tracing.tags.url"))
      val methodTagMode = TagMode.from(config.getString("tracing.tags.method"))
      val statusCodeTagMode = TagMode.from(config.getString("tracing.tags.status-code"))
      val contextTags = config.getConfig("tracing.tags.from-context").pairs.map {
        case (tagName, mode) => (tagName, TagMode.from(mode))
      }

      val traceIDResponseHeader = optionalString(config.getString("tracing.response-headers.trace-id"))
      val spanIDResponseHeader = optionalString(config.getString("tracing.response-headers.span-id"))

      val unhandledOperationName = config.getString("tracing.operations.unhandled")
      val operationMappings = config.getConfig("tracing.operations.mappings").pairs.map {
        case (pattern, operationName) => (new Filter.Glob(pattern), operationName)
      }

      Settings(
        enablePropagation,
        propagationChannel,
        enableServerMetrics,
        enableTracing,
        traceIdTag,
        enableSpanMetrics,
        urlTagMode,
        methodTagMode,
        statusCodeTagMode,
        contextTags,
        traceIDResponseHeader,
        spanIDResponseHeader,
        unhandledOperationName,
        operationMappings
      )
    }
  }
}

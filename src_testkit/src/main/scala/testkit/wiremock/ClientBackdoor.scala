package chessfinder
package testkit.wiremock

import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.{ MappingBuilder, ResponseDefinitionBuilder, WireMock }
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.google.common.net.HttpHeaders.CONTENT_TYPE
import testkit.wiremock.ClientBackdoor.Context
import scala.util.Try
import zio.test.TestResult
import zio.test.assertZIO
import zio.test.Assertion

class ClientBackdoor(basePath: String):

  def expectsEndpoint(method: String, url: String): Context.RequestContext =
    val fullUrl    = basePath + url
    val urlPattern = WireMock.urlEqualTo(fullUrl)
    val builder    = WireMock.request(method, urlPattern)
    Context.RequestContext(builder)

  def verify(count: Int, method: RequestMethod, url: String): zio.Task[Unit] =
    val fullUrl        = basePath + url
    val urlPattern     = WireMock.urlEqualTo(fullUrl)
    val requestPattern = new RequestPatternBuilder(method, urlPattern)
    for
      _ <- zio.ZIO.attempt(WireMock.configureFor("localhost", 18443)) // FIXME make me configurable
      _ <- zio.ZIO.attempt(WireMock.verify(WireMock.moreThanOrExactly(count), requestPattern))
    yield ()

  def verify(count: Int, method: String, url: String): zio.Task[Unit] =
    verify(count, RequestMethod(method), url)

  def check(count: Int, method: String, url: String): zio.Task[TestResult] =
    assertZIO(verify(count, RequestMethod(method), url))(Assertion.isUnit)

object ClientBackdoor:

  sealed abstract class Context[SELF <: Context[SELF]]:
    protected def mappingBuilder: MappingBuilder
    protected def responseDefinitionBuilder: ResponseDefinitionBuilder

    protected def update(mappingBuilder: MappingBuilder): SELF

    protected def update(
        responseDefinitionBuilder: ResponseDefinitionBuilder
    ): Context.ResponseContext

    def expectsJsonBody(json: String): SELF =
      this.update(
        mappingBuilder.withRequestBody(WireMock.equalToJson(json, true, true))
      )

    def expectsXmlBody(xml: String): SELF =
      this.update(mappingBuilder.withRequestBody(WireMock.equalToXml(xml)))

    def returnsStatusCode(code: Int): Context.ResponseContext =
      this.update(responseDefinitionBuilder.withStatus(code))

    def returnsJson(json: String): Context.ResponseContext =
      this.update(
        responseDefinitionBuilder
          .withHeader(CONTENT_TYPE, "application/json")
          .withBody(json)
      )

    def returnsXml(xml: String): Context.ResponseContext =
      this.update(
        responseDefinitionBuilder
          .withHeader(CONTENT_TYPE, "text/xml; charset=utf-8")
          .withBody(xml)
      )

  object Context:

    case class RequestContext(protected val mappingBuilder: MappingBuilder) extends Context[RequestContext]:

      protected val responseDefinitionBuilder: ResponseDefinitionBuilder =
        ResponseDefinitionBuilder.responseDefinition()

      override protected def update(
          mappingBuilder: MappingBuilder
      ): RequestContext = this.copy(mappingBuilder = mappingBuilder)

      override protected def update(
          responseDefinitionBuilder: ResponseDefinitionBuilder
      ): ResponseContext =
        ResponseContext(mappingBuilder, responseDefinitionBuilder)

    case class ResponseContext(
        protected val mappingBuilder: MappingBuilder,
        protected val responseDefinitionBuilder: ResponseDefinitionBuilder
    ) extends Context[ResponseContext]:

      def stub(): zio.Task[StubMapping] =
        for
          _    <- zio.ZIO.attempt(WireMock.configureFor("localhost", 18443))  // FIXME make me configurable
          stub <- zio.ZIO.attempt(WireMock.stubFor(mappingBuilder.willReturn(responseDefinitionBuilder)))
        yield stub

      override protected def update(
          mappingBuilder: MappingBuilder
      ): ResponseContext =
        this.copy(
          mappingBuilder = mappingBuilder,
          responseDefinitionBuilder = this.responseDefinitionBuilder
        )

      override protected def update(
          responseDefinitionBuilder: ResponseDefinitionBuilder
      ): ResponseContext =
        this.copy(
          mappingBuilder = this.mappingBuilder,
          responseDefinitionBuilder = responseDefinitionBuilder
        )

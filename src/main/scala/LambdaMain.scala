package chessfinder

import zio.ZIOApp
import zio.ZIOAppDefault

import sttp.tapir.ztapir.*
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.http.{ HttpApp, Request, Response }
import zio.*
import zio.http.{ App as _, * }
import chessfinder.search.GameFinder
import sttp.apispec.openapi.Server as OAServer
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.swagger.*
import sttp.tapir.redoc.*
import sttp.tapir.redoc.RedocUIOptions
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.server.*
import chessfinder.search.BoardValidator
import chessfinder.search.GameFetcher
import chessfinder.search.Searcher
import chessfinder.search.TaskStatusChecker
import chessfinder.search.GameDownloader
import chessfinder.search.ArchiveDownloader
import chessfinder.client.chess_com.ChessDotComClient
import sttp.tapir.serverless.aws.lambda.LambdaHandler

import cats.effect.unsafe.implicits.global
import com.amazonaws.services.lambda.runtime.Context
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.serverless.aws.lambda.{ AwsRequest, LambdaHandler }
import java.io.{ InputStream, OutputStream }
import cats.implicits.*
import sttp.tapir.serverless.aws.lambda.zio.ZLambdaHandler
import zio.Task
import zio.{ Task, ZIO }
import cats.effect.unsafe.implicits.global
import com.amazonaws.services.lambda.runtime.Context
import io.circe.generic.auto.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.serverless.aws.lambda.{ AwsRequest, LambdaHandler }
import java.io.{ InputStream, OutputStream }
import sttp.tapir.serverless.aws.lambda.zio.ZLambdaHandler
import sttp.tapir.ztapir.ZServerEndpoint
import sttp.tapir.ztapir.RIOMonadError
import zio.{ Runtime, Unsafe }
import chessfinder.api.{ AsyncController, SyncController }
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import zio.logging.*
import chessfinder.client.ZLoggingAspect
import zio.logging.backend.SLF4J
import chessfinder.api.ApiVersion
import chessfinder.search.repo.{ GameRepo, TaskRepo, UserRepo }
import zio.aws.netty
import zio.aws.core.config.AwsConfig
import persistence.core.DefaultDynamoDBExecutor
import zio.dynamodb.*
import util.EndpointCombiner
import zio.config.typesafe.TypesafeConfigProvider
import sttp.tapir.serverless.aws.lambda.zio.AwsZServerOptions
import search.queue.GameDownloadingProducer
import pubsub.DownloadGameCommand

object LambdaMain extends BaseMain with RequestStreamHandler:

  override protected val configLayer = Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  private val handler =
    def options[R] =
      AwsZServerOptions.noEncoding[R](
        AwsZServerOptions
          .customiseInterceptors[R]
          .serverLog(serverLogger)
          .options
      )
    ZLambdaHandler.withMonadError(
      EndpointCombiner.many(syncController.rest, asyncController.rest),
      options
    )

  def process(input: InputStream, output: OutputStream) =
    val hand = handler
      .process[AwsRequest](input, output)

    (for
      _ <- ZIO.logInfo("BEFORE $$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
      r <- hand
      _ <- ZIO.logInfo("AFTER $$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
    yield r).provide(
      clientLayer,
      BoardValidator.Impl.layer,
      GameFinder.Impl.layer[ApiVersion.Newborn.type],
      GameFinder.Impl.layer[ApiVersion.Async.type],
      Searcher.Impl.layer,
      GameFetcher.Impl.layer,
      GameFetcher.Local.layer,
      ChessDotComClient.Impl.layer,
      UserRepo.Impl.layer,
      GameRepo.Impl.layer,
      TaskRepo.Impl.layer,
      TaskStatusChecker.Impl.layer,
      ArchiveDownloader.Impl.layer,
      GameDownloadingProducer.Impl.layer,
      DownloadGameCommand.Queue.layer,
      dynamodbLayer,
      sqsLayer,
      ZLayer.succeed(zio.Random.RandomLive)
    )

  override def handleRequest(input: InputStream, output: OutputStream, context: Context): Unit =
    Unsafe.unsafe { implicit unsafe =>
      val runtime = Runtime.unsafe.fromLayer(configLayer >+> loggingLayer)
      runtime.unsafe
        .run(
          process(input, output) @@ aspect.BuildInfo.log @@ aspect.CorrelationId
            .log(context.getAwsRequestId())
        )
        .getOrThrowFiberFailure()
    }

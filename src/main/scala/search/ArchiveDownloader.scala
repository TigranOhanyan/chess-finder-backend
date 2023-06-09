package chessfinder
package search

import api.{ TaskResponse, TaskStatusResponse }
import aspect.Span
import client.ClientError
import client.ClientError.ProfileNotFound
import client.chess_com.ChessDotComClient
import client.chess_com.dto.{ Archives, Games }
import persistence.{ GameRecord, PlatformType, UserRecord }
import pubsub.DownloadGameCommand
import search.BrokenLogic
import search.BrokenLogic.{ NoGameAvailable, ServiceOverloaded }
import search.entity.*
import search.queue.GameDownloadingProducer
import search.repo.*
import sttp.model.Uri

import chess.format.pgn.PgnStr
import izumi.reflect.Tag
import zio.dynamodb.*
import zio.{ Random, UIO, ZIO, ZLayer }

import scala.annotation.tailrec

trait ArchiveDownloader:

  def cache(user: User): φ[TaskId]

object ArchiveDownloader:

  class Impl(
      client: ChessDotComClient,
      userRepo: UserRepo,
      archiveRepo: ArchiveRepo,
      taskRepo: TaskRepo,
      gameDownloadingCommandProducer: GameDownloadingProducer,
      random: Random
  ) extends ArchiveDownloader:

    def cache(user: User): φ[TaskId] =
      val gettingProfile = client
        .profile(user.userName)
        .mapError {
          case ClientError.ProfileNotFound(userName) => BrokenLogic.ProfileNotFound(user)
          case _                                     => BrokenLogic.ServiceOverloaded
        }
        .map(profile => user.identified(UserId(profile.`@id`.toString)))

      val gettingArchives = client
        .archives(user.userName)
        .mapError {
          case ClientError.ProfileNotFound(userName) => BrokenLogic.ProfileNotFound(user)
          case _                                     => BrokenLogic.ServiceOverloaded
        }
        .filterOrFail(_.archives.nonEmpty)(NoGameAvailable(user))

      for
        userIdentified <- gettingProfile
        _              <- userRepo.save(userIdentified)
        allArchives    <- gettingArchives

        cachedArchiveResults <- archiveRepo.getAll(userIdentified.userId)
        cachedArchives = cachedArchiveResults.map(_.resource).toSet
        fullyDownloadedArchives = cachedArchiveResults
          .filter(_.status == ArchiveStatus.FullyDownloaded)
          .map(_.resource)
          .toSet
        shouldBeDownloadedArchives = allArchives.archives.filterNot(resource =>
          fullyDownloadedArchives.contains(resource)
        )
        missingArchives = allArchives.archives.filterNot(resource => cachedArchives.contains(resource))
        _      <- archiveRepo.initiate(userIdentified.userId, missingArchives)
        taskId <- random.nextUUID.map(uuid => TaskId(uuid))
        _      <- taskRepo.initiate(taskId, shouldBeDownloadedArchives.length)
        shouldBeDownloadedArchivesIds = shouldBeDownloadedArchives.map(resource =>
          ArchiveId(resource.toString)
        )
        _ <- gameDownloadingCommandProducer.publish(userIdentified, shouldBeDownloadedArchivesIds, taskId)
      yield taskId

  @Deprecated
  object Impl:
    val layer = ZLayer {
      for
        client                          <- ZIO.service[ChessDotComClient]
        userRepo                        <- ZIO.service[UserRepo]
        taskRepo                        <- ZIO.service[TaskRepo]
        archiveRepo                     <- ZIO.service[ArchiveRepo]
        gameDownloadingCommandPublisher <- ZIO.service[GameDownloadingProducer]
        random                          <- ZIO.service[Random]
      yield Impl(client, userRepo, archiveRepo, taskRepo, gameDownloadingCommandPublisher, random)
    }

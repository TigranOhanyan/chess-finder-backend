package chessfinder
package search

import zio.test.*
import chessfinder.core.SearchFen
import chessfinder.core.ProbabilisticBoard
import search.BrokenLogic.*
import search.GameDownloader
import search.entity.*
import sttp.model.Uri.UriContext
import client.chess_com.dto.*
import chess.format.pgn.PgnStr
import zio.mock.Expectation
import api.ApiVersion
import core.SearchFen
import chessfinder.api.TaskResponse
import chessfinder.util.UriParser
import java.util.UUID
import zio.mock.MockRandom
import client.ClientError
import sttp.model.Uri
import chess.format.pgn.PgnStr
import zio.mock.MockReporter

object GameDownloaderTest extends ZIOSpecDefault with Mocks:

  override def spec = suite("GameDownloader")(
    suite("cache")(
      test(
        "when a valid user is prvided should get the profie of the user, then archives and launch a process of downloading games (this can't be check since it is launched as a deamon fiber)"
      ) {
        val platform = ChessPlatform.ChessDotCom
        val userName = UserName("user")
        val user     = User(platform, userName)
        val expectedProfile =
          Profile(`@id` = uri"https://api.chess.com/pub/player/tigran-c-137")

        val getProfile = ChessDotComClientMock.ProfileMethod(
          assertion = Assertion.equalTo(userName),
          result = Expectation.value(expectedProfile)
        )

        val userIdentified = user.identified(UserId(expectedProfile.`@id`.toString))

        val saveUser = UserRepoMock.SaveUser(
          assertion = Assertion.equalTo(userIdentified),
          result = Expectation.value(())
        )

        val expectedArchive =
          Archives(Seq(uri"https://example.com/archive/1", uri"https://example.com/archive/2"))

        val getArchiveCall = ChessDotComClientMock.ArchivesMethod(
          assertion = Assertion.equalTo(userName),
          result = Expectation.value(expectedArchive)
        )

        val expectedUuid = UUID.randomUUID()

        val generateTaskId = MockRandom.NextUUID(
          Expectation.value(expectedUuid)
        )

        val expectedTaskId = TaskId(expectedUuid)

        val initiatingTask = TaskRepoMock.InitiateTask(
          assertion = Assertion.equalTo((expectedTaskId, expectedArchive.archives.length)),
          result = Expectation.unit
        )

        val mock = (getProfile ++ saveUser ++ getArchiveCall ++ generateTaskId ++ initiatingTask).toLayer

        val caching = GameDownloader
          .cache(user)

        (for
          actualResult <- caching
          check = assertTrue(actualResult == expectedTaskId)
        yield check).provide(mock, GameRepoMock.empty, GameDownloader.Impl.layer)
      },
      test("when user profile is not found should return ProfileNotFound") {

        val platform = ChessPlatform.ChessDotCom
        val userName = UserName("user")
        val user     = User(platform, userName)
        val expectedProfile =
          Profile(`@id` = UriParser.apply("https://api.chess.com/pub/player/tigran-c-137").get)

        val getProfile = ChessDotComClientMock.ProfileMethod(
          assertion = Assertion.equalTo(userName),
          result = Expectation.failure(ClientError.ProfileNotFound(userName))
        )

        val mock = getProfile.toLayer

        val caching = GameDownloader
          .cache(user)

        (for
          actualResult <- caching.either
          check = assertTrue(actualResult == Left(ProfileNotFound(user)))
        yield check)
          .provide(
            mock,
            UserRepoMock.empty,
            TaskRepoMock.empty,
            GameRepoMock.empty,
            GameDownloader.Impl.layer,
            MockRandom.empty
          )
      },
      test("when user profile is found but archives are not available should return ProfileNotFound") {

        val platform = ChessPlatform.ChessDotCom
        val userName = UserName("user")
        val user     = User(platform, userName)
        val expectedProfile =
          Profile(`@id` = UriParser.apply("https://api.chess.com/pub/player/tigran-c-137").get)

        val getProfile = ChessDotComClientMock.ProfileMethod(
          assertion = Assertion.equalTo(userName),
          result = Expectation.value(expectedProfile)
        )

        val userIdentified = user.identified(UserId(expectedProfile.`@id`.toString))

        val saveUser = UserRepoMock.SaveUser(
          assertion = Assertion.equalTo(userIdentified),
          result = Expectation.value(())
        )

        val getArchiveCall = ChessDotComClientMock.ArchivesMethod(
          assertion = Assertion.equalTo(userName),
          result = Expectation.failure(ClientError.ProfileNotFound(userName))
        )

        val mock = (getProfile ++ saveUser ++ getArchiveCall).toLayer

        val caching = GameDownloader
          .cache(user)

        (for
          actualResult <- caching.either
          check = assertTrue(actualResult == Left(ProfileNotFound(user)))
        yield check)
          .provide(
            mock,
            TaskRepoMock.empty,
            GameRepoMock.empty,
            GameDownloader.Impl.layer,
            MockRandom.empty
          )
      },
      test(
        "when a valid user is prvided archives are empty should return NoGameAvaliable"
      ) {
        val platform = ChessPlatform.ChessDotCom
        val userName = UserName("user")
        val user     = User(platform, userName)
        val expectedProfile =
          Profile(`@id` = UriParser.apply("https://api.chess.com/pub/player/tigran-c-137").get)

        val getProfile = ChessDotComClientMock.ProfileMethod(
          assertion = Assertion.equalTo(userName),
          result = Expectation.value(expectedProfile)
        )

        val userIdentified = user.identified(UserId(expectedProfile.`@id`.toString))

        val saveUser = UserRepoMock.SaveUser(
          assertion = Assertion.equalTo(userIdentified),
          result = Expectation.value(())
        )

        val expectedArchive =
          Archives(Seq.empty[Uri])

        val getArchiveCall = ChessDotComClientMock.ArchivesMethod(
          assertion = Assertion.equalTo(userName),
          result = Expectation.value(expectedArchive)
        )

        val mock = (getProfile ++ saveUser ++ getArchiveCall).toLayer

        val caching = GameDownloader
          .cache(user)

        (for
          actualResult <- caching.either
          check = assertTrue(actualResult == Left(NoGameAvaliable(user)))
        yield check)
          .provide(mock, GameRepoMock.empty, TaskRepoMock.empty, GameDownloader.Impl.layer, MockRandom.empty)
      }
    ),
    suite("download")(
      test(
        "when a valid user is prvided, archives are avaliable and task is initiated should download games"
      ) {
        val platform       = ChessPlatform.ChessDotCom
        val userName       = UserName("user")
        val user           = User(platform, userName)
        val userId         = UserId("https://api.chess.com/pub/player/tigran-c-137")
        val userIdentified = user.identified(userId)
        val taskId         = TaskId(UUID.randomUUID())

        val archives =
          Archives(
            Seq(
              uri"https://example.com/archive/1",
              uri"https://example.com/archive/2",
              uri"https://example.com/archive/3"
            )
          )

        val expectedDownloadedGame1 = Game(uri"https://example.com/1", "Game1")
        val historicalGame1         = HistoricalGame(uri"https://example.com/1", PgnStr("Game1"))

        val expectedDownloadedGame2 = Game(uri"https://example.com/2", "Game2")
        val historicalGame2         = HistoricalGame(uri"https://example.com/2", PgnStr("Game2"))

        val expectedDownloadedArchive1 = Games(Seq(expectedDownloadedGame1, expectedDownloadedGame2))

        val downloadFirstArchive = ChessDotComClientMock.GamesMethod(
          assertion = Assertion.equalTo(uri"https://example.com/archive/1"),
          result = Expectation.value(expectedDownloadedArchive1)
        )

        val saveFirstArchive = GameRepoMock.SaveGames(
          assertion = Assertion.equalTo((userId, Seq(historicalGame1, historicalGame2))),
          result = Expectation.unit
        )

        val incrementSuccess1 = TaskRepoMock.SuccessIncrement(
          assertion = Assertion.equalTo(taskId),
          result = Expectation.unit
        )

        val expectedDownloadedGame3 = Game(uri"https://example.com/3", "Game3")
        val historicalGame3         = HistoricalGame(uri"https://example.com/3", PgnStr("Game3"))

        val expectedDownloadedArchive2 = Seq(expectedDownloadedGame3)

        val downloadSecondArchive = ChessDotComClientMock.GamesMethod(
          assertion = Assertion.equalTo(uri"https://example.com/archive/2"),
          result = Expectation.value(Games(expectedDownloadedArchive2))
        )

        val saveSecondArchive = GameRepoMock.SaveGames(
          assertion = Assertion.equalTo((userId, Seq(historicalGame3))),
          result = Expectation.unit
        )

        val incrementSuccess2 = TaskRepoMock.SuccessIncrement(
          assertion = Assertion.equalTo(taskId),
          result = Expectation.unit
        )

        val downloadThiredArchive = ChessDotComClientMock.GamesMethod(
          assertion = Assertion.equalTo(uri"https://example.com/archive/3"),
          result = Expectation.failure(ClientError.SomethingWentWrong)
        )

        val incrementfailure = TaskRepoMock.FailureIncrement(
          assertion = Assertion.equalTo(taskId),
          result = Expectation.unit
        )

        val mock =
          (downloadFirstArchive ++ saveFirstArchive ++ incrementSuccess1 ++ downloadSecondArchive ++ saveSecondArchive ++ incrementSuccess2 ++ downloadThiredArchive ++ incrementfailure).toLayer

        val downloading = GameDownloader
          .download(userIdentified, archives, taskId)
          .debug

        (for
          actualResult <- downloading
          check = assertTrue(actualResult == ())
        yield check).provide(mock, UserRepoMock.empty, GameDownloader.Impl.layer, MockRandom.empty)
      }
    )
  ) @@ TestAspect.sequential @@ MockReporter()

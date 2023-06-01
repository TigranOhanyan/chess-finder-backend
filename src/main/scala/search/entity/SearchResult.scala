package chessfinder
package search.entity

import search.entity.MatchedGames
import sttp.model.Uri

import ornicar.scalalib.newtypes

import java.time.Instant

// final case class SearchResult(matched: MatchedGames, status: DownloadStatus)
final case class SearchResult(
    id: SearchRequestId,
    startSearchAt: Instant,
    lastExaminedAt: Instant,
    examined: Int,
    total: Int,
    matched: MatchedGames,
    status: SearchStatus
):

  def update(
      lastExaminedAt: Instant,
      lastExamined: Int,
      lastMatched: MatchedGames
  ): SearchResult =
    val totalExamined = examined + lastExamined
    val finalStatus   = if total == totalExamined then SearchStatus.SearchedAll else SearchStatus.InProgress
    val finalMatched  = lastMatched ++ matched
    this.copy(
      lastExaminedAt = lastExaminedAt,
      examined = totalExamined,
      matched = finalMatched,
      status = finalStatus
    )

  def doFinalize: SearchResult =
    this.copy(status = if total == examined then SearchStatus.SearchedAll else SearchStatus.SearchedPartially)

object SearchResult:

  def apply(
      id: SearchRequestId,
      startSearchAt: Instant,
      total: Int
  ): SearchResult =
    SearchResult(
      id = id,
      startSearchAt = startSearchAt,
      lastExaminedAt = startSearchAt,
      examined = 0,
      total = total,
      matched = Seq.empty[MatchedGame],
      status = if total == 0 then SearchStatus.SearchedAll else SearchStatus.InProgress
    )

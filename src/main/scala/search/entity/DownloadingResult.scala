package chessfinder
package search.entity

import cats.data.NonEmptySeq
import sttp.model.Uri

case class FetchingResult(games: Seq[HistoricalGame], unreachableArchives: Seq[Uri])

object FetchingResult:
  val empty = FetchingResult(List.empty, List.empty)

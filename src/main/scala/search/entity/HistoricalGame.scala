package chessfinder
package search.entity

import chess.format.pgn.PgnStr
import sttp.model.Uri
import chess.format.pgn.Reader
import Reader.Result
import core.β

case class HistoricalGame(resource: Uri, pgn: PgnStr)

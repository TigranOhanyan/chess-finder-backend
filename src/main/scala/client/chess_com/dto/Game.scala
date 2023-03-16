package chessfinder
package client.chess_com.dto

import sttp.model.Uri

final case class Game(
    url: Uri,
    pgn: String
)
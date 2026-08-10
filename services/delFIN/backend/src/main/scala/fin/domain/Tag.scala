package fin.domain

import java.util.UUID
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

case class Tag(
  id: UUID,
  name: String,
  color: String,
  icon: Option[String]
)

object Tag:
  given Encoder[Tag] = deriveEncoder
  given Decoder[Tag] = deriveDecoder

case class CreateTag(name: String, color: String, icon: Option[String])

object CreateTag:
  given Decoder[CreateTag] = deriveDecoder

case class UpdateTag(name: String, color: String, icon: Option[String])

object UpdateTag:
  given Decoder[UpdateTag] = deriveDecoder

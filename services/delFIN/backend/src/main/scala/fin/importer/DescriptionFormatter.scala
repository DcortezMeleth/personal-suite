package fin.importer

/** Derives a short, human-readable transaction title from a raw imported
  * description, independent of how that raw text was produced. Kept separate
  * from the parsers so the display format can change without re-importing —
  * `raw_description` is the durable source of truth; this is purely a view
  * over it, safe to re-run against already-imported data at any time.
  */
object DescriptionFormatter:

  // Structured field 86 uses "<NN" tags (PKO BP) or "~NN" (other banks) to mark
  // subfields. Tag 20 typically carries the merchant/payment title, tag 27 the
  // counterparty name.
  private val subfieldPattern = """[~<](\d{2})([^~<]*)""".r

  private def extractSubfields(raw: String): Map[String, String] =
    subfieldPattern.findAllMatchIn(raw).map(m => m.group(1) -> m.group(2).trim).toMap

  private def sanitise(raw: String): String =
    raw.replaceAll("""[~<]\d{2}""", " ")
       .replaceAll("""\s+""", " ")
       .trim
       .take(500)

  /** `rawDescription` is expected in the "label\nbody" shape produced by the
    * MT940 parser (label may be absent), or a single-line raw dump from other
    * formats (e.g. Alior CSV), which simply won't match any subfield tag.
    */
  private def splitLabelAndBody(rawDescription: String): (String, String) =
    rawDescription.split("\n", 2) match
      case Array(l, b) => (l.trim, b)
      case Array(only) => ("", only)
      case _           => ("", "")

  // Some transfers put a structured payment reference (tax ID, period code, ...)
  // in tag 20 instead of free text, e.g. "/TI/P92071402674" — that's not a useful
  // title, so treat a leading "/" as a signal to prefer the type label instead.
  private def looksLikeReferenceCode(s: String): Boolean = s.startsWith("/")

  def extractTitle(rawDescription: String): String =
    val (label, body) = splitLabelAndBody(rawDescription)
    val subfields = extractSubfields(body)
    val tag20 = subfields.get("20").filter(_.nonEmpty).filterNot(looksLikeReferenceCode)
    val title = tag20
      .orElse(if label.nonEmpty then Some(label) else None)
      .orElse(subfields.get("27").filter(_.nonEmpty))
      .getOrElse(sanitise(body))
    title.take(140)

  val GenericCardFeeTitle = "Transakcja karta platnicza"

  /** The counterparty (payee/payer) name — whoever the money actually went to
    * or came from. Tag 27 for transfers; for a card purchase, which has no
    * separate counterparty concept, the merchant itself (tag 20) fills this
    * role too — "who did I pay" should never read as empty just because the
    * source format only had one named field instead of two. Excludes the
    * boilerplate card-fee phrase and reference-code-shaped values, neither of
    * which name an actual entity.
    */
  def extractCounterparty(rawDescription: String): Option[String] =
    val (_, body) = splitLabelAndBody(rawDescription)
    val subfields = extractSubfields(body)
    subfields.get("27").filter(_.nonEmpty)
      .orElse(
        subfields.get("20").filter(_.nonEmpty)
          .filterNot(looksLikeReferenceCode)
          .filterNot(_.equalsIgnoreCase(GenericCardFeeTitle))
      )
      .map(_.trim.take(140))

  // PKO BP's card-fee lines carry no merchant reference of their own — tag 20 is
  // always this exact boilerplate phrase. A fee line always immediately follows
  // the card transaction it's charging a fee for in the statement, so we borrow
  // that transaction's title AND counterparty instead of showing this useless
  // placeholder and an empty counterparty.
  //
  // This is a SEPARATE pass over an already-ordered sequence (rather than folded
  // into extractTitle/extractCounterparty themselves) so the exact same logic
  // can run both at import time (natural statement order) and later,
  // retroactively, over already-imported rows ordered by (account, date,
  // imported_at) — a display fix should never require re-importing the file.
  def threadFeeInfo(entries: Seq[(String, Option[String])]): Seq[(String, Option[String])] =
    var lastMeaningful: Option[(String, Option[String])] = None
    entries.map { case (title, counterparty) =>
      if title.equalsIgnoreCase(GenericCardFeeTitle) then
        lastMeaningful match
          case Some((prevTitle, prevCounterparty)) => (s"Card fee — $prevTitle", prevCounterparty)
          case None                                => (title, counterparty)
      else
        lastMeaningful = Some((title, counterparty))
        (title, counterparty)
    }

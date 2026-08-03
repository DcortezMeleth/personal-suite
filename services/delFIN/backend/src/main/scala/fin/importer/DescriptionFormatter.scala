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
  def extractTitle(rawDescription: String): String =
    val (label, body) = rawDescription.split("\n", 2) match
      case Array(l, b) => (l.trim, b)
      case Array(only) => ("", only)
      case _            => ("", "")
    val subfields = extractSubfields(body)
    val title = subfields.get("27").filter(_.nonEmpty)
      .orElse(subfields.get("20").filter(_.nonEmpty))
      .getOrElse(if label.nonEmpty then label else sanitise(body))
    title.take(140)

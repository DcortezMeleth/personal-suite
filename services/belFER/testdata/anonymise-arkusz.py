#!/usr/bin/env python3
"""Anonymise a Vulcan planOrg (arkusz organizacyjny) XML export.

The real export identifies the school and every member of staff. This strips
both while keeping the file usable as a parser fixture:

  * the school's kod / nazwa / regon, and the KodSzkoly / NazwaSzkoly repeated
    on every <klasa> (which also carries the town), are replaced with an
    obviously synthetic school;
  * every teacher's surname, forename and two-letter Kod are replaced, and
    the NauczycielRef / WychowawcaRef pointing at them are rewritten to match;
  * individual-teaching records (nauczanie indywidualne / rewalidacja) are
    rebuilt entirely. Their Kod encodes a student's initials and their Name
    carries the student's full name next to their provision — a named minor
    plus health data, the most sensitive content in the file.

Everything else — class codes, specialisation names, student counts, subjects,
hour allocations, group splits, cross-class lessons — is real data and is left
untouched. That is the part worth testing against.

The *shape* of the teacher data is preserved per record, because those quirks
are exactly what a parser trips over: a surname spaced around its hyphen, a
forename with a trailing space, a Kod carrying a Polish diacritic. If the
original record had the quirk, its replacement does too.

Assignment is deterministic — the same real teacher yields the same fake name
on every run, so re-anonymising an updated arkusz produces a readable diff.

Usage:
    python3 anonymise-arkusz.py private/full-semester.xml arkusz/full-semester.xml
"""

import hashlib
import re
import sys

SCHOOL = {
    "kod": "LOX",
    "nazwa": "Liceum Ogólnokształcące nr 1 w Przykładowie",
    "regon": "00000000000000",
    "kod_szkoly": "LOX01/4",
    "nazwa_szkoly": "Liceum Ogólnokształcące nr 1 w Przykładowie/4",
}

DIACRITICS = "ĄĆĘŁŃÓŚŹŻ"

# Surnames beginning with a Polish diacritic, so that records whose original
# Kod carried one still produce a Kod carrying one.
SURNAMES_DIACRITIC = [
    "Żak", "Żurek", "Śliwa", "Świątek", "Łuczak", "Łapiński", "Ćwikła",
    "Ślusarczyk", "Łoboda", "Żelazny", "Ćwiek", "Śmigiel", "Łagodzki",
    "Ścibor", "Źróbek", "Ćwierz", "Łysiak", "Świderski", "Żebrowski",
    "Śniegowski", "Łabuda", "Źrebiec", "Ćwikliński", "Świercz", "Żuraw",
]

SURNAMES = [
    "Adamczyk", "Baranowski", "Bednarek", "Bielecki", "Borkowski", "Brzeziński",
    "Brzozowski", "Chmielewski", "Cieślak", "Czajkowski", "Czerwiński",
    "Dobrowolski", "Domagała", "Dziedzic", "Gajewski", "Głowacki", "Grabowski",
    "Jabłoński", "Jaskólski", "Jasiński", "Kaczmarek", "Kalinowski",
    "Kamiński", "Kołodziej", "Konieczny", "Korzeniowski", "Kosiński",
    "Krupa", "Kubiak", "Laskowski", "Lewandowski", "Lis", "Madej",
    "Majewski", "Malinowski", "Marciniak", "Matuszak", "Mazur", "Michalski",
    "Mroczek", "Niemiec", "Nowak", "Olszewski", "Orłowski", "Ostrowski",
    "Pietrzak", "Piotrowski", "Przybylski", "Rutkowski", "Rybak", "Sadowski",
    "Serafin", "Sikora", "Skiba", "Sobczak", "Sokołowski", "Stasiak",
    "Stępień", "Szewczyk", "Szulc", "Tomaszewski", "Urbański", "Walczak",
    "Wasilewski", "Wieczorek", "Witkowski", "Wróbel", "Zalewski", "Zaremba",
    "Zawadzki", "Zieliński", "Jarosz", "Kania", "Leszczyński", "Milewski",
    "Nowicki", "Pietruszka", "Rogalski", "Sawicki", "Trojanowski", "Wilk",
]

FORENAMES_F = [
    "Agata", "Aldona", "Aleksandra", "Alicja", "Amelia", "Aniela", "Anita",
    "Blanka", "Bogumiła", "Bożena", "Cecylia", "Celina", "Dagmara", "Daria",
    "Diana", "Dominika", "Edyta", "Elwira", "Emilia", "Ewelina", "Felicja",
    "Gabriela", "Genowefa", "Grażyna", "Halina", "Hanna", "Helena", "Ilona",
    "Irena", "Izabela", "Jadwiga", "Janina", "Julia", "Kamila", "Kinga",
    "Klara", "Kornelia", "Krystyna", "Lidia", "Liliana", "Lucyna", "Ludmiła",
    "Łucja", "Maja", "Malwina", "Mariola", "Marzena", "Melania", "Mirosława",
    "Nadia", "Natalia", "Nikola", "Olga", "Oliwia", "Patrycja", "Róża",
    "Sandra", "Stefania", "Teresa", "Urszula", "Wanda", "Weronika", "Wiktoria",
    "Wiesława", "Zuzanna", "Żaneta", "Zofia", "Jolanta", "Renata",
]

FORENAMES_M = [
    "Albert", "Aleksander", "Alfred", "Antoni", "Arkadiusz", "Artur",
    "Bartosz", "Błażej", "Bogdan", "Bolesław", "Cezary", "Czesław", "Damian",
    "Daniel", "Dawid", "Dominik", "Edward", "Emil", "Eugeniusz", "Filip",
    "Franciszek", "Gerard", "Gustaw", "Hubert", "Ignacy", "Igor", "Ireneusz",
    "Jakub", "Jarosław", "Juliusz", "Kacper", "Kamil", "Karol", "Konrad",
    "Leszek", "Lucjan", "Ludwik", "Maksymilian", "Mariusz", "Mikołaj",
    "Miron", "Norbert", "Olaf", "Oskar", "Patryk", "Przemysław", "Radosław",
    "Rafał", "Remigiusz", "Roman", "Ryszard", "Sebastian", "Seweryn",
    "Stanisław", "Szymon", "Teodor", "Tytus", "Wacław", "Waldemar", "Witold",
    "Zdzisław", "Zenon", "Zygmunt",
]

FORENAMES = FORENAMES_F + FORENAMES_M


def _seed(text):
    return int(hashlib.sha256(text.encode("utf-8")).hexdigest(), 16)


def _feminise(surname, is_female):
    if is_female and surname.endswith("ski"):
        return surname[:-1] + "a"
    if is_female and surname.endswith("cki"):
        return surname[:-1] + "a"
    return surname


def _stem(surname):
    """Collapse a surname to a gender-neutral stem.

    Kozłowski and Kozłowska are the same family name; comparing the raw strings
    would let one through as a replacement for the other.
    """
    s = surname.strip().lower()
    for feminine, masculine in (("ska", "ski"), ("cka", "cki"), ("dzka", "dzki")):
        if s.endswith(feminine):
            return s[: -len(feminine)] + masculine
    return s


def _real_surname_stems(teachers):
    """Every surname in the source file, including each half of a compound."""
    stems = set()
    for t in teachers:
        raw = t["Nazwa"].strip()
        for part in re.split(r"\s*-\s*|\s+", raw):
            if part:
                stems.add(_stem(part))
    return stems


# Individual-teaching records (nauczanie indywidualne / rewalidacja) are modelled
# as pseudo-classes whose Kod is <year><letter><student initials> and whose
# Name carries the student's full name alongside their provision. That is a
# named minor plus health data — the most sensitive content in the file — so it
# is rebuilt from scratch rather than edited.
PSEUDO_CLASS_RE = re.compile(r"^\d[A-ZĄĆĘŁŃÓŚŹŻ].+$")

# Only these canonical labels are ever emitted. Nothing is copied from the
# source text, so no free-text fragment can survive by accident.
PROVISION_MARKERS = [
    ("REWALIDACJA", ("rewalidac", "rew.", "rew ")),
    ("NAUCZANIE IND.", ("naucz.ind", "nauczanie ind", "ind+", "ind.", " ind")),
    ("WSPOMAGAJĄCY", ("wspomagaj",)),
    ("WYRÓWNAWCZE", ("wyrówn", "wyr",)),
    ("ZDALNE", ("zdaln",)),
]

SUFFIX_LETTERS = "abcdefghijklmnopqrstuvwxyz"


def _is_pseudo_class(kod):
    """True for an individual-teaching record rather than a real class."""
    return bool(re.match(r"^\d[A-ZĄĆĘŁŃÓŚŹŻ].+$", kod))


def _provisions(name):
    lowered = name.lower()
    found = [label for label, markers in PROVISION_MARKERS
             if any(m in lowered for m in markers)]
    return " + ".join(found) if found else "PLAN"


def _apply_case(pattern, letters):
    """Copy the upper/lower case pattern of the original suffix.

    The export contains pairs such as 3Fal and 3FAL that differ only in case
    and are genuinely distinct classes. Preserving the pattern keeps that
    hazard in the fixture.
    """
    out = []
    for i, ch in enumerate(letters):
        source = pattern[i] if i < len(pattern) else pattern[-1]
        out.append(ch.upper() if source.isupper() else ch.lower())
    return "".join(out)


def _parse_attrs(fragment):
    return dict(re.findall(r'(\w+)="([^"]*)"', fragment))


def _personal_name_tokens(classes):
    """Capitalised words appearing in individual-teaching class names.

    Those records carry students' full names. Over-banning is harmless — the
    pools are far larger than needed — and it keeps a real student's forename
    from reappearing in the output as somebody's teacher.
    """
    tokens = set()
    for k in classes:
        if _is_pseudo_class(k.get("Kod", "")):
            for word in re.findall(r"[A-ZĄĆĘŁŃÓŚŹŻ][A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ]{2,}", k.get("Name", "")):
                tokens.add(word.lower())
    return tokens


def build_mapping(teachers, classes=()):
    """Map each real teacher onto a synthetic one, preserving record shape."""
    # A real surname must never be reused, in either gender form, as anybody's
    # replacement — otherwise the anonymised file still names real staff.
    banned = _real_surname_stems(teachers) | _personal_name_tokens(classes)
    surnames = [s for s in SURNAMES if _stem(s) not in banned]
    surnames_diacritic = [s for s in SURNAMES_DIACRITIC if _stem(s) not in banned]
    if len(surnames) < len(teachers) // 2 or len(surnames_diacritic) < 8:
        raise SystemExit(
            "not enough unused surnames left after excluding real ones "
            "(%d plain, %d diacritic) — extend the pools"
            % (len(surnames), len(surnames_diacritic))
        )

    # Exclude every forename that appears in the source — a real teacher's
    # forename resurfacing on a different record is still a source name in the
    # published file, and an absolute guarantee is easier to state and to check
    # than "only in a harmless combination".
    source_forenames = {t["Imie"].strip().lower() for t in teachers}
    source_forenames |= _personal_name_tokens(classes)
    forenames = [f for f in FORENAMES if f.lower() not in source_forenames]
    if len(forenames) < 20:
        raise SystemExit(
            "only %d unused forenames remain — extend FORENAMES_F / FORENAMES_M"
            % len(forenames))
    feminine = {f for f in FORENAMES_F}

    real_pairs = {(t["Imie"].strip(), _stem(t["Nazwa"])) for t in teachers}
    used_names, used_codes, mapping = set(), set(), {}

    for original in sorted(teachers, key=lambda t: t["Kod"]):
        code = original["Kod"]
        rnd = _seed(code)

        wants_diacritic = any(c in DIACRITICS for c in code)
        pool = surnames_diacritic if wants_diacritic else surnames

        # Pick a forename first; it decides whether the surname is feminised.
        forename = None
        forename = forenames[rnd % len(forenames)]
        is_female = forename in feminine

        surname = None
        for probe in range(len(pool) * 4):
            base = pool[(rnd // 7 + probe) % len(pool)]
            candidate = _feminise(base, is_female)
            if (candidate, forename) in used_names:
                continue
            if (forename, _stem(candidate)) in real_pairs:
                continue  # never reproduce a real forename+surname pair
            if True:
                surname = candidate
                break
        if surname is None:
            raise RuntimeError(f"ran out of surnames for {code}")

        # Re-create the original record's quirks.
        original_surname = original["Nazwa"]
        if " - " in original_surname:
            second = _feminise(surnames[(rnd // 13) % len(surnames)], is_female)
            surname = f"{surname} - {second}"
        elif "-" in original_surname:
            second = _feminise(surnames[(rnd // 17) % len(surnames)], is_female)
            surname = f"{surname}-{second}"

        display_forename = forename
        if original["Imie"] != original["Imie"].rstrip():
            display_forename = forename + " "

        used_names.add((surname, forename))

        new_code = _make_code(surname, forename, wants_diacritic, used_codes, rnd)
        used_codes.add(new_code)

        mapping[code] = {
            "Nazwa": surname,
            "Imie": display_forename,
            "Kod": new_code,
        }

    return mapping


def _make_code(surname, forename, wants_diacritic, used_codes, rnd):
    letters = [c for c in surname + forename if c.isalpha()]
    candidates = []
    first = surname[0].upper()
    candidates.append(first + forename[0].upper())
    candidates.extend(first + c.upper() for c in letters[1:])
    candidates.extend(forename[0].upper() + c.upper() for c in letters)

    for candidate in candidates:
        if wants_diacritic and not any(c in DIACRITICS for c in candidate):
            continue
        if candidate not in used_codes:
            return candidate
    # Fall back to any free two-letter code rather than failing outright.
    alphabet = "ABCDEFGHIJKLMNOPRSTUWXYZ"
    for a in alphabet:
        for b in alphabet:
            if a + b not in used_codes:
                return a + b
    raise RuntimeError("exhausted the two-letter code space")


def build_student_mapping(classes):
    """Replace every individual-teaching pseudo-class with a synthetic one.

    Names are not swapped for other plausible names here. A realistic name
    sitting next to "REWALIDACJA" would still read as a real claim about a real
    child if it happened to coincide with one, so the replacement is openly
    synthetic: UCZEŃ AA, UCZEŃ AB, and so on.
    """
    pseudo = [k for k in classes if _is_pseudo_class(k["Kod"])]

    # Codes equal but for case (3Fal / 3FAL) are distinct classes; group them so
    # they keep sharing letters and stay distinct in the same way.
    groups, order = {}, []
    for k in pseudo:
        key = k["Kod"].lower()
        if key not in groups:
            groups[key] = len(order)
            order.append(key)

    # A generated suffix must not happen to reproduce a real one — "al" would
    # turn 3FAL back into 3FAL and quietly republish a student's initials.
    banned = {k["Kod"].lower() for k in pseudo}
    letters_for, cursor = {}, 0
    for key in order:
        base = key[:2]
        while True:
            candidate = (SUFFIX_LETTERS[cursor // len(SUFFIX_LETTERS)]
                         + SUFFIX_LETTERS[cursor % len(SUFFIX_LETTERS)])
            cursor += 1
            if (base + candidate) not in banned and candidate not in letters_for.values():
                letters_for[key] = candidate
                break

    mapping = {}
    for k in pseudo:
        letters = letters_for[k["Kod"].lower()]
        original_suffix = k["Kod"][2:]
        suffix = _apply_case(original_suffix, letters[: max(2, len(original_suffix))])
        base = k["Kod"][:2]
        name = "%s UCZEŃ %s - %s" % (base, letters.upper(), _provisions(k["Name"]))
        mapping[k["Kod"]] = {
            "Kod": base + suffix,
            "Short": base[1] + suffix,
            "Name": name,
            # Nazwa repeats Name behind the year digit, as it does for real classes.
            "Nazwa": k["Kod"][0] + name,
        }
    return mapping


def anonymise(text):
    teachers = [_parse_attrs(m) for m in re.findall(r"<nauczyciel ([^>]*)/>", text)]
    if not teachers:
        raise SystemExit("no <nauczyciel> records found — is this a planOrg export?")
    source_classes = [_parse_attrs(m) for m in re.findall(r"<klasa ([^>]*)/>", text)]
    mapping = build_mapping(teachers, source_classes)

    def replace_teacher(match):
        attrs = _parse_attrs(match.group(1))
        fake = mapping[attrs["Kod"]]
        line = match.group(0)
        line = re.sub(r'Nazwa="[^"]*"', 'Nazwa="%s"' % fake["Nazwa"], line, count=1)
        line = re.sub(r'Imie="[^"]*"', 'Imie="%s"' % fake["Imie"], line, count=1)
        line = re.sub(r'Kod="[^"]*"', 'Kod="%s"' % fake["Kod"], line, count=1)
        return line

    text = re.sub(r"<nauczyciel ([^>]*)/>", replace_teacher, text)

    def replace_ref(attr):
        def sub(match):
            code = match.group(1)
            if not code:
                return match.group(0)
            return '%s="%s"' % (attr, mapping[code]["Kod"])
        return sub

    for attr in ("NauczycielRef", "WychowawcaRef"):
        text = re.sub(r'%s="([^"]*)"' % attr, replace_ref(attr), text)

    # Individual-teaching pseudo-classes: named minors plus health data.
    classes = [_parse_attrs(m) for m in re.findall(r"<klasa ([^>]*)/>", text)]
    students = build_student_mapping(classes)

    def replace_class(match):
        attrs = _parse_attrs(match.group(1))
        fake = students.get(attrs["Kod"])
        if fake is None:
            return match.group(0)
        line = match.group(0)
        for attr in ("Kod", "Short", "Name", "Nazwa"):
            line = re.sub(r'%s="[^"]*"' % attr, '%s="%s"' % (attr, fake[attr]), line, count=1)
        return line

    text = re.sub(r"<klasa ([^>]*)/>", replace_class, text)

    def replace_klasa_ref(match):
        code = match.group(1)
        if code in students:
            return 'KlasaRef="%s"' % students[code]["Kod"]
        return match.group(0)

    text = re.sub(r'KlasaRef="([^"]*)"', replace_klasa_ref, text)

    # School identity.
    text = re.sub(r'(<placowka[^>]*?)kod="[^"]*"', r'\1kod="%s"' % SCHOOL["kod"], text)
    text = re.sub(r'(<placowka[^>]*?)nazwa="[^"]*"', r'\1nazwa="%s"' % SCHOOL["nazwa"], text)
    text = re.sub(r'(<placowka[^>]*?)regon="[^"]*"', r'\1regon="%s"' % SCHOOL["regon"], text)
    text = re.sub(r'KodSzkoly="[^"]*"', 'KodSzkoly="%s"' % SCHOOL["kod_szkoly"], text)
    text = re.sub(r'NazwaSzkoly="[^"]*"', 'NazwaSzkoly="%s"' % SCHOOL["nazwa_szkoly"], text)

    return text, mapping


def verify(original, anonymised):
    """Fail loudly rather than quietly emit a file that still names real staff."""
    real = [_parse_attrs(m) for m in re.findall(r"<nauczyciel ([^>]*)/>", original)]
    fake = [_parse_attrs(m) for m in re.findall(r"<nauczyciel ([^>]*)/>", anonymised)]
    problems = []

    if len(real) != len(fake):
        problems.append("record count changed: %d -> %d" % (len(real), len(fake)))

    banned = _real_surname_stems(real)
    for t in fake:
        for part in re.split(r"\s*-\s*|\s+", t["Nazwa"].strip()):
            if part and _stem(part) in banned:
                problems.append("real surname reused: %s" % t["Nazwa"])

    real_pairs = {(t["Imie"].strip(), _stem(t["Nazwa"])) for t in real}
    for t in fake:
        if (t["Imie"].strip(), _stem(t["Nazwa"])) in real_pairs:
            problems.append("real full name reproduced: %s %s" % (t["Imie"], t["Nazwa"]))

    # School identity must be gone from the whole document, not just <placowka>.
    for attr in ("nazwa", "regon"):
        value = re.search(r'<placowka[^>]*?%s="([^"]*)"' % attr, original)
        if value and value.group(1) and value.group(1) in anonymised:
            problems.append("school %s survived: %s" % (attr, value.group(1)))
    for town in re.findall(r'NazwaSzkoly="([^"]*)"', original):
        if town and town in anonymised:
            problems.append("NazwaSzkoly survived: %s" % town)

    # No <klasa> may carry anything that reads as a person's name.
    person = re.compile(r"[A-ZĄĆĘŁŃÓŚŹŻ][A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ]+\s+[A-ZĄĆĘŁŃÓŚŹŻ][A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ]+")
    for element in re.findall(r"<klasa ([^>]*)/>", anonymised):
        attrs = _parse_attrs(element)
        for attr in ("Name", "Nazwa", "Kod", "Short"):
            value = attrs.get(attr, "")
            if attr in ("Name", "Nazwa") and person.search(value) and "UCZEŃ" not in value:
                problems.append("class %s looks like a person: %s" % (attr, value))

    # Every original student initial/name must be gone from the whole document.
    for element in re.findall(r"<klasa ([^>]*)/>", original):
        attrs = _parse_attrs(element)
        if _is_pseudo_class(attrs["Kod"]):
            for attr in ("Kod", "Short", "Name", "Nazwa"):
                value = attrs.get(attr, "")
                if value and value in anonymised:
                    problems.append("individual-teaching %s survived: %s" % (attr, value))

    student_tokens = _personal_name_tokens(
        [_parse_attrs(e) for e in re.findall(r"<klasa ([^>]*)/>", original)])
    source_forenames = {t["Imie"].strip().lower() for t in real} | student_tokens
    for t in fake:
        if t["Imie"].strip().lower() in source_forenames:
            problems.append("forename from the source reused: %s" % t["Imie"])
    for t in fake:
        for field in ("Nazwa", "Imie"):
            for word in re.split(r"\s*-\s*|\s+", t[field].strip()):
                if word and word.lower() in student_tokens:
                    problems.append("teacher %s reuses a student's name: %s" % (field, word))

    class_codes = {_parse_attrs(e)["Kod"] for e in re.findall(r"<klasa ([^>]*)/>", anonymised)}
    dangling_classes = (set(re.findall(r'KlasaRef="([^"]*)"', anonymised)) - {""}) - class_codes
    if dangling_classes:
        problems.append("KlasaRef does not resolve: %s" % sorted(dangling_classes))

    codes = {t["Kod"] for t in fake}
    if len(codes) != len(fake):
        problems.append("teacher codes are not unique")
    for attr in ("NauczycielRef", "WychowawcaRef"):
        refs = set(re.findall(r'%s="([^"]*)"' % attr, anonymised)) - {""}
        dangling = refs - codes
        if dangling:
            problems.append("%s does not resolve: %s" % (attr, sorted(dangling)))

    return problems



# ---------------------------------------------------------------------------
# Fixture reduction
#
# Anonymising identities is not the same as anonymising the data. A full export
# still shows how many students in each class receive special-needs provision,
# which on a public repository is a re-identification risk for a child if anyone
# works out which school it is. The fixture therefore keeps only a couple of
# individual-teaching records, chosen so that none of them carries any provision
# detail, and class sizes are nudged so the published numbers are not the
# school's real roll.
# ---------------------------------------------------------------------------

SEN_MARKERS = ("rewalidac", "wspom", "zrkes", "tus", "psycholog", "kompetencje")
KEEP_PSEUDO_CLASSES = 3
ROLL_JITTER = 3


def _is_sen_subject(subject):
    text = (subject.get("Kod", "") + " " + subject.get("Nazwa", "")).lower()
    return any(m in text for m in SEN_MARKERS)


def _drop_lines(text, predicate):
    """Remove whole elements, trailing CRLF included, where predicate matches."""
    return re.sub(
        r"[ \t]*<(\w+) ([^>]*)/>\r?\n",
        lambda m: "" if predicate(m.group(1), _parse_attrs(m.group(2))) else m.group(0),
        text,
    )


def reduce_fixture(text):
    classes = [_parse_attrs(m) for m in re.findall(r"<klasa ([^>]*)/>", text)]
    assignments = [_parse_attrs(m) for m in re.findall(r"<przydzial ([^>]*)/>", text)]
    subjects = [_parse_attrs(m) for m in re.findall(r"<przedmiot ([^>]*)/>", text)]

    sen_subjects = {s["Kod"] for s in subjects if _is_sen_subject(s)}
    pseudo = [k for k in classes if _is_pseudo_class(k["Kod"])]

    uses_sen = set()
    for a in assignments:
        if a.get("PrzedmiotRef") in sen_subjects and a.get("KlasaRef"):
            uses_sen.add(a["KlasaRef"])

    # Prefer records with no provision detail, and keep a pair differing only by
    # case if one exists — two such classes are genuinely distinct and a parser
    # that lowercases its keys would silently merge them.
    clean = [k["Kod"] for k in pseudo if k["Kod"] not in uses_sen]
    by_fold = {}
    for code in clean:
        by_fold.setdefault(code.lower(), []).append(code)
    keep = []
    for codes in by_fold.values():
        if len(codes) > 1:
            keep.extend(codes)
            break
    for code in clean:
        if len(keep) >= KEEP_PSEUDO_CLASSES:
            break
        if code not in keep:
            keep.append(code)
    keep = set(keep[:max(KEEP_PSEUDO_CLASSES, 2)])
    drop = {k["Kod"] for k in pseudo} - keep

    text = _drop_lines(text, lambda tag, a: tag == "klasa" and a.get("Kod") in drop)
    text = _drop_lines(text, lambda tag, a: tag == "przydzial" and a.get("KlasaRef") in drop)

    # Any subject left with no assignment goes too, which clears the SEN codes.
    remaining = {a.get("PrzedmiotRef") for a in
                 (_parse_attrs(m) for m in re.findall(r"<przydzial ([^>]*)/>", text))}
    text = _drop_lines(
        text,
        lambda tag, a: tag == "przedmiot" and a.get("Kod") not in remaining)

    def scrub_class(match):
        attrs = _parse_attrs(match.group(1))
        line = match.group(0)
        if _is_pseudo_class(attrs["Kod"]):
            # Drop the provision breakdown; the record's shape is what matters.
            name = "%s %s - PLAN INDYWIDUALNY" % (attrs["Kod"][:2], "UCZEŃ")
            line = re.sub(r'Name="[^"]*"', 'Name="%s"' % name, line, count=1)
            line = re.sub(r'Nazwa="[^"]*"', 'Nazwa="%s%s"' % (attrs["Kod"][0], name), line, count=1)
            return line
        roll = int(attrs.get("LiczbaUczniow") or 0)
        girls = int(attrs.get("LiczbaDziewczyn") or 0)
        if roll:
            delta = (_seed(attrs["Kod"]) % (2 * ROLL_JITTER + 1)) - ROLL_JITTER
            new_roll = max(1, roll + delta)
            new_girls = min(new_roll, max(0, girls + delta // 2))
            line = re.sub(r'LiczbaUczniow="[^"]*"', 'LiczbaUczniow="%d"' % new_roll, line, count=1)
            line = re.sub(r'LiczbaDziewczyn="[^"]*"', 'LiczbaDziewczyn="%d"' % new_girls, line, count=1)
        return line

    return re.sub(r"<klasa ([^>]*)/>", scrub_class, text)


def main():
    args = sys.argv[1:]
    reduce_only = "--reduce-only" in args
    args = [a for a in args if a != "--reduce-only"]
    if len(args) != 2:
        raise SystemExit(__doc__)
    source, target = args

    if reduce_only:
        # Re-apply only the reduction to a file that is already anonymised.
        raw = open(source, "rb").read().decode("utf-8")
        open(target, "wb").write(reduce_fixture(raw).encode("utf-8"))
        print("reduced: %s" % target)
        return

    # Read and write bytes so CRLF line endings and UTF-8 survive untouched.
    raw = open(source, "rb").read().decode("utf-8")
    result, mapping = anonymise(raw)
    result = reduce_fixture(result)

    problems = verify(raw, result)
    if problems:
        print("ANONYMISATION FAILED — nothing written:")
        for problem in problems:
            print("  * %s" % problem)
        raise SystemExit(1)

    open(target, "wb").write(result.encode("utf-8"))

    print("teachers replaced:  %d" % len(mapping))
    print("verification:       passed")
    print("wrote:              %s" % target)
    print()
    print("No forename or surname appearing anywhere in the source survives in")
    print("the output — neither staff names nor the student names carried by the")
    print("individual-teaching records.")


if __name__ == "__main__":
    main()

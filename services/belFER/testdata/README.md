# belFER test data

Fixtures shared by tests and by manual exploration. Wired into Bazel as the
`//services/belFER/testdata:testdata` filegroup.

## Layout

| Directory | Contents |
|---|---|
| `arkusz/` | Samples of the principal's XML assignment sheet — **anonymised**, committed |
| `plans/` | Expected-output fixtures and small solver scenarios |
| `private/` | **Gitignored.** Scratch space for real, non-anonymised files |

## Where to put the XML

Drop anonymised samples in `arkusz/`, named for what they exercise:

```
arkusz/full-year.xml          a complete, representative year
arkusz/splits.xml             a class with split subjects
arkusz/pe-merged.xml          classes merged for PE
arkusz/unknown-subject.xml    an entity belFER does not know (UC-25)
```

## Anonymising

A real arkusz names the school and every member of staff. Keep the original in
`private/` — gitignored, and excluded from the Bazel filegroup so no test can
come to depend on a file that exists on only one machine — then run:

```sh
python3 anonymise-arkusz.py private/full-semester.xml arkusz/full-semester.xml
```

It replaces the school's `kod` / `nazwa` / `regon`, the `KodSzkoly` /
`NazwaSzkoly` repeated on every `<klasa>` (which also carries the town), and
every teacher's surname, forename and `Kod` — rewriting `NauczycielRef` and
`WychowawcaRef` to match.

It also rebuilds the **individual-teaching records** (*nauczanie indywidualne*,
*rewalidacja*). Those are modelled as pseudo-classes whose `Kod` encodes a
student's initials and whose `Name` carries the student's full name beside
their provision — a named minor plus health data, and the most sensitive
content in the file by some distance. They become `4Caa` / `"4C UCZEŃ AA -
REWALIDACJA"`, with the provision rebuilt from a fixed vocabulary so no
free text is ever copied through. Everything else is real and stays: class codes,
specialisations, student counts, subjects, hour allocations, group splits,
cross-class lessons.

Three properties worth knowing:

* **It verifies itself and refuses to write on failure.** A real surname being
  reused, a real forename+surname pair reappearing, school identity surviving,
  or a dangling teacher reference all abort the run.
* **Record shape is preserved per teacher.** A surname spaced around its
  hyphen, a forename with a trailing space, a `Kod` carrying a diacritic — if
  the real record had the quirk, its replacement does too, because those are
  precisely what a parser trips over.
* **No name from the source survives anywhere in the output** — not staff
  names, and not the student names carried by the individual-teaching records.
  Both pools are filtered against the input before anything is assigned.

Assignment is deterministic, so re-anonymising an updated arkusz yields a
readable diff rather than 75 unrelated renames.

## Reduction

Anonymising identities is not the same as anonymising the data. A full export
still shows how many students in each class receive special-needs provision —
on a public repository, a re-identification risk for a child if anyone works
out which school it is.

So the same script also reduces the fixture, and runs the reduction as part of
every anonymisation:

* **three individual-teaching records are kept instead of twenty-one**, chosen
  so none of them carries provision detail, and so that a pair differing only
  in case survives (`3Fak` / `3FAK` are genuinely distinct classes, and a
  parser that lowercases its keys would silently merge them);
* subjects left with no assignment are dropped, which clears the special-needs
  codes;
* class rolls are nudged by a few students, so the published numbers are not
  the school's real roll.

Everything else is untouched real data. To re-apply only this step to a file
that is already anonymised:

```sh
python3 anonymise-arkusz.py --reduce-only in.xml out.xml
```

## Consuming it from a test

Add the filegroup to the test target's `resources`:

```python
scala_test(
    name = "unit_test",
    srcs = glob(["src/test/scala/**/*.scala"]),
    resources = ["//services/belFER/testdata:testdata"],
    deps = [...],
)
```

Resources keep their repo-relative path on the classpath — the same mechanism
that lets Flyway find `classpath:db/migrations` from `//db:migrations` — so a
file is read as:

```scala
getClass.getResourceAsStream("/services/belFER/testdata/arkusz/full-year.xml")
```

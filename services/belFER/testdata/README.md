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

A real arkusz contains staff names. **Anonymise before committing**, or keep
the original in `private/`, which is gitignored and deliberately excluded from
the Bazel filegroup so no test can come to depend on a file that exists on
only one machine.

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

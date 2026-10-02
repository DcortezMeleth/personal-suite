---
name: run-belfer
description: Launch and drive the belFER app locally — Postgres + Scala/http4s backend (Bazel) + Vite frontend — and smoke-test it in the browser. Use when asked to run, start, or screenshot belFER, or to verify a change against the running app rather than only tests.
---

# Running belFER locally

Three processes: **Postgres** (shared with delFIN), **backend** (`bazel run`,
port **8081**), **frontend** (Vite dev server, port **3001**). delFIN holds
8080 and 3000, so both can run at once.

The frontend proxies `/api` and `/health` → `localhost:8081`, so open **only**
`http://localhost:3001`.

Docker Compose is not the local dev path, and belFER has no compose entry yet.

## 1. Postgres

Shared launchd Postgres (`homebrew.mxcl.postgresql@14`), normally already up.
belFER lives in its own **`belfer` schema** inside the `delfin` database —
delFIN has the `delfin` schema. Flyway creates the schema on first run, so
there is no setup step. Verify:

```bash
psql -h localhost -U delfin -d delfin -c '\dn'
```

The server is **PostgreSQL 14**, not the 16 named in TECH_STACK.md. A migration
using a 15+ feature will fail here.

## 2. Backend

`BELFER_DATABASE_PASSWORD` is a required HOCON substitution with no default —
the JVM exits at startup rather than trying an empty password. The value lives
in `infra/.env` as `POSTGRES_PASSWORD`.

Note the `BELFER_` prefix: delFIN reads the unprefixed `DATABASE_*`, and the
two must not be shared or pointing one service at another database would move
both.

```bash
set -a && . infra/.env && set +a && export BELFER_DATABASE_PASSWORD="$POSTGRES_PASSWORD" && \
  bazel run //services/belFER/backend:server
```

Cold Bazel analysis takes a couple of minutes; a warm run is seconds. Readiness:

```bash
curl -sf http://localhost:8081/health      # {"status":"ok","service":"belFER"}
```

`/health` is at the root, **not** under `/api`, so a readiness probe does not
depend on the API routes being wired up.

## 3. Frontend

`pnpm` is not on PATH. Run Vite directly:

```bash
cd services/belFER/frontend && ./node_modules/.bin/vite
```

If `node_modules` is missing (a fresh clone, or a new workspace package), run
`npx --yes pnpm@11 install` from the repo root first.

## Verifying a change

Open `http://localhost:3001`. With an empty database the school page offers
**Utwórz szkołę** — one click gives a school with defaults, after which the
bell schedule and scheduling rules are editable.

To inspect what was actually stored:

```bash
psql -h localhost -U delfin -d delfin -c 'SELECT * FROM belfer.school;'
psql -h localhost -U delfin -d delfin -c 'SELECT position, starts_at, ends_at FROM belfer.time_slot ORDER BY position;'
```

## Known gotchas

- `bazel build //services/belFER/...` (the whole package glob) currently fails
  type-checking. Named targets work; so does `bazel test`. delFIN's package
  glob fails the same way and worse, so this predates belFER.
- The container image is cross-built for linux/amd64 and cannot be exec'd on an
  Apple Silicon host without emulation.

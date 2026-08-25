---
name: run-delfin
description: Launch and drive the delFIN app locally — Postgres + Scala/http4s backend (Bazel) + Vite frontend — and smoke-test it in the browser. Use when asked to run, start, or screenshot the app, or to verify a change against the running app rather than only tests.
---

# Running delFIN locally

Three processes: **Postgres** (already installed natively), **backend**
(`bazel run`, port 8080), **frontend** (Vite dev server, port 3000).
The frontend proxies `/api` → `localhost:8080`, so open **only**
`http://localhost:3000` — never Vite's port-forwarded backend directly.

Docker Compose (`infra/docker-compose.yml`) is *not* the local dev path.
Colima usually isn't running, and the compose `backend`/`frontend` services
expect images that Bazel has to build first. Skip it.

## 1. Postgres

Runs as a launchd agent (`homebrew.mxcl.postgresql@14`, data dir
`/usr/local/var/postgresql@14`), so it is normally up already. Verify:

```bash
psql -h localhost -U delfin -d delfin -c 'select count(*) from transactions;'
```

If it fails to connect: `/usr/local/bin/brew services start postgresql@14`.

Note the server is **PostgreSQL 14**, not the 16 that TECH_STACK.md and the
compose file name. All 13 Flyway migrations apply cleanly on 14 — but if a new
migration uses a 15+/16-only feature, it will fail here and not in CI.

## 2. Backend

`application.conf` has no default for `DATABASE_PASSWORD` — it is a required
substitution, so the JVM dies at startup if it is unset. The password lives in
`infra/.env` as `POSTGRES_PASSWORD`; export it under the name the config wants:

```bash
set -a && . infra/.env && set +a && export DATABASE_PASSWORD="$POSTGRES_PASSWORD" && \
  bazel run //services/delFIN/backend:server
```

Run it in the background and tee to a log — it never exits on its own. A cold
Bazel analysis takes ~2 min even on a warm action cache; incremental runs are
seconds. Wait for readiness rather than sleeping:

```bash
until curl -sf http://localhost:8080/health >/dev/null 2>&1 \
   || grep -qE 'Exception|error:|BUILD FAILED' backend.log; do sleep 2; done
```

Ready looks like:

```
INFO org.flywaydb...DbMigrate -- Schema "public" is up to date. No migration necessary.
INFO ...EmberServerBuilder -- Ember-Server service bound to address: [::]:8080
```

Route prefixes (`Main.scala`): health is mounted at **`/health`**, everything
else under **`/api`**. `GET /api/health` 404s — don't use it as the probe.

Smoke-test endpoints:

```bash
curl -s http://localhost:8080/health
curl -s http://localhost:8080/api/accounts
curl -s "http://localhost:8080/api/spending/summary?from=2026-01-01&to=2026-12-31"
```

## 3. Frontend

`pnpm` is **not on PATH** (no corepack either), but `node_modules` is already
installed in the workspace. Call Vite's binary directly instead of `pnpm dev`:

```bash
cd services/delFIN/frontend && ./node_modules/.bin/vite
```

Ready when it prints `VITE v6.x ready` and `Local: http://localhost:3000/`.
Only run `pnpm install` if a dependency actually changed — and then you first
need pnpm 11 (`npx pnpm@11 install --frozen-lockfile`).

## 4. Drive it in the browser

Use the `claude-in-chrome` tools against `http://localhost:3000`.

**The dashboard defaults to the current month, which is usually empty** — the
seeded data sits in 2026-05/06, so a fresh load legitimately shows
`0,00 zł` and a "No transactions in <month>" banner. That is not a failure.
Click **All Time** (or pick a month with data) before judging any screenshot.
With All Time selected you should see non-zero totals, a category pie, the
12-month trend bars, and the tag bar chart.

**Recharts animates on mount**: the category pie starts as a thin horizontal
line and grows. It usually settles in ~2s but has taken 5s+, so don't trust a
fixed sleep — if a chart looks empty or line-thin, wait and re-screenshot
before concluding anything. Only call it broken if it is still flat after
~10s. The same applies to the tag bar chart.

**A click issued immediately after `navigate` gets dropped** — React hasn't
hydrated yet. Screenshot first to confirm the control is there, and re-click if
the UI didn't change; a silently ignored click looks exactly like a broken
button.

Worth clicking through for most changes: Dashboard (All Time), Transactions
(106 rows, category dropdowns, tag filters), then `read_console_messages` with
`onlyErrors: true` — call it *before* the page load you want logs for, since
tracking starts at first call.

## 5. Teardown

Stop the two background tasks (backend, Vite). Leave Postgres running — it is a
system service, not part of this app's lifecycle.

# delFIN — Tech Stack Reference

## Build System

> **Versions live in `MODULE.bazel` and `.bazelversion`, not here.** This file
> used to restate them and every row had drifted — the table claimed Bazel
> 9.1.1 two lines above the sentence saying `.bazelversion` pins 9.2.0. What a
> build file cannot say is *why*, so that is what this file keeps.

| Tool | Notes |
|---|---|
| Bazel | bzlmod (MODULE.bazel), not legacy WORKSPACE |
| rules_scala | Scala 3 support |
| rules_jvm_external | Maven artifact resolution |
| aspect_rules_js | Node/npm/pnpm support |
| aspect_rules_ts | TypeScript compilation |
| rules_oci | Container image building |
| bazel_skylib | Common Bazel utilities |
| rules_pkg | Packaging |

`.bazelrc` enables bzlmod, sets JVM 25, disables Java header compilation.

---

## Backend

Again, `MODULE.bazel` holds the versions.

| Library | Purpose |
|---|---|
| Scala | Language — 3.3.x LTS, see the note below |
| http4s-ember-server | HTTP server |
| http4s-ember-client | HTTP client (Eurostat, dane.gov.pl) |
| http4s-circe | JSON response encoding |
| http4s-dsl | Route DSL |
| cats-effect | Async runtime |
| cats-core | Functional core |
| fs2-core / fs2-io | Streaming |
| circe-core/generic/parser | JSON codec |
| doobie-core/postgres/hikari | Database access |
| postgresql JDBC | PostgreSQL driver |
| Flyway | Schema migrations |
| logback-classic | Logging backend |
| log4cats-slf4j | Functional logging |
| Typesafe Config | Application config (`application.conf`) |
| ip4s-core | Host/port types |

### The cats constraint, and why it is gone

This file used to say **do not upgrade cats-effect past 3.5.7 or cats-core past
2.9.0**: cats-core 2.12.0 shipped `TupleParallelSyntax` with a TASTy encoding
that Scala 3 compilers could not read, and anything pulling cats-core 2.12+ onto
the classpath crashed the compiler. That was real, and it pinned the whole
Typelevel stack behind it — fs2, http4s, ip4s, vault, keypool, cats-mtl all
stayed where they were because cats could not move.

It no longer reproduces. On 2026-10-27, under Scala 3.3.8, cats-core 2.13.0 and
cats-effect 3.7.1 compile and pass both services' tests, and both servers serve
identical responses against the real database. The pin is lifted.

Worth keeping the shape of the lesson: a constraint discovered the hard way is
worth writing down, and worth **re-testing before believing**, because the fix
arrives upstream without telling anyone. Re-testing cost one build. The pin had
been holding back a dozen libraries.

### Explicit transitive deps required

Bazel's `rules_jvm_external` resolves transitive deps for the JVM runtime but does **not** add them to the Scala compilation classpath automatically. Every library whose types appear during compilation must be listed explicitly in the `deps` of the target's `BUILD.bazel`. For the backend target this includes: `vault`, `case-insensitive`, `cats-mtl`, `cats-parse`, `keypool`, `literally`, `fs2-core`, `fs2-io`, `ip4s-core`.

### No inline macros

ip4s literal interpolators (`ipv4"..."`, `port"..."`) are Scala 3 inline macros that fail when the library is loaded via Bazel's header-jar mechanism. Use the runtime equivalents instead:

```scala
Host.fromString("0.0.0.0").get
Port.fromInt(8080).get
```

Apply the same caution to any other library that uses Scala 3 inline macros.

---

## Database

| Component | Choice |
|---|---|
| Database | PostgreSQL 16 |
| Migrations | Flyway (applied at server startup via `FlywayMigrator`) |
| Schema location | `db/migrations/V*__*.sql` |
| Access layer | Doobie (functional, type-safe queries) |
| Connection pool | HikariCP (via doobie-hikari) |

---

## Frontend

| Tool | Version | Purpose |
|---|---|---|
| Node | 22 (LTS) | Runtime; required by pnpm 11 |
| pnpm | 11 | Package manager + workspace orchestration |
| React | 18 | UI framework |
| TypeScript | 5.7 | Type safety |
| Vite | 6 | Dev server + bundler |
| Tailwind CSS | 3 | Utility-first styling |
| Recharts | 2 | Charts (pie, bar, line) |
| react-router-dom | 6 | Client-side routing |

### Workspace layout

pnpm workspaces are declared in `pnpm-workspace.yaml`:

```yaml
packages:
  - "libs/*"
  - "services/*/frontend"
```

Each package has its own `package.json`. The root `package.json` holds shared devDependencies (TypeScript, etc.).

### TypeScript config

`tsconfig.base.json` at repo root sets shared compiler options:

- `target: ES2022`
- `jsx: react-jsx` (new transform — no `import React` needed)
- `moduleResolution: bundler`
- `strict: true`
- `noUnusedLocals: true`

Each package extends `tsconfig.base.json`.

---

## Shared UI Library (`@delfin/ui`)

Location: `libs/ui/`

Provides the homelab design system consumed by all service frontends:

| Export | Type |
|---|---|
| `AppHeader` | Component — top navigation bar |
| `PageLayout` | Component — sidebar + content layout |
| `DataCard` | Component — metric tile with label/value/delta |
| `AlertBanner` | Component — budget/system alert bar |
| `colors` | Token map — brand palette |
| `typography` | Token map — font sizes + weights |

The Vite alias `@delfin/ui → ../../../libs/ui/src/index.ts` is set in each service's `vite.config.ts` so the dev server resolves the library from source without a build step.

---

## Infrastructure

| Component | Image / Tool |
|---|---|
| PostgreSQL | `postgres:16-alpine` |
| Backend JVM | `eclipse-temurin:25-jre-noble` |
| Frontend (prod) | `nginx:alpine` serving the Vite build output |
| Orchestration | Docker Compose (`infra/docker-compose.yml`) |

The `postgres` service starts unconditionally. `backend` and `frontend` are gated behind `profiles: [full]` so `docker compose up postgres -d` works for local development without building the full stack.

---

## External APIs

| Data | API | Auth |
|---|---|---|
| Poland CPI (inflation) | Eurostat REST API | None |
| Treasury bond rates | dane.gov.pl open data | None |

---

## Key Architectural Decisions

**Bazel for polyglot monorepo** — Single build graph for Scala backend and TypeScript frontend. Bazel tracks cross-language dependencies and rebuilds only what changes. Each future homelab service follows the same pattern under `services/`.

**cats-effect IO runtime** — All I/O (HTTP, DB, file parsing, scheduled jobs) runs inside `cats.effect.IO`. Doobie integrates natively; http4s Ember runs as a `Resource`.

**Doobie for DB access** — Type-safe, composable SQL fragments. No ORM magic; queries are plain SQL with Scala interpolation. Migration SQL lives separately in `db/migrations/` and Flyway applies them on startup.

**Valuation at query time** — Treasury bonds and bank deposits compute accrued interest server-side on every request rather than storing point-in-time snapshots. This keeps the data model simple and ensures values are always current.

**Rule-based auto-categorisation** — Stored in the database as `category_rules` with CONTAINS / REGEX / EXACT match types, evaluated in order (first match wins). User corrections generate new rules, so the system improves over time without ML complexity.

**Inter-account transfer detection** — Matched by amount + ±2-day date window across all household accounts. Both sides are flagged `is_internal_transfer = true` and excluded from income/spending totals. User can confirm or break the link.

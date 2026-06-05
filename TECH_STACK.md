# delFIN — Tech Stack Reference

## Build System

| Tool | Version | Notes |
|---|---|---|
| Bazel | 9.1.1 | bzlmod (MODULE.bazel), not legacy WORKSPACE |
| rules_scala | 7.2.5 | Scala 3 support |
| rules_jvm_external | 7.0 | Maven artifact resolution |
| aspect_rules_js | 3.2.0 | Node/npm/pnpm support |
| aspect_rules_ts | 3.8.10 | TypeScript compilation |
| rules_oci | 2.3.0 | Container image building |
| bazel_skylib | 1.9.0 | Common Bazel utilities |
| rules_pkg | 1.2.0 | Packaging |

`.bazelversion` pins to `9.1.1`. `.bazelrc` enables bzlmod, sets JVM 17, disables Java header compilation.

---

## Backend

| Library | Version | Purpose |
|---|---|---|
| Scala | **3.3.7 LTS** | Language — see version constraint note below |
| http4s-ember-server | 0.23.30 | HTTP server |
| http4s-ember-client | 0.23.30 | HTTP client (Eurostat, dane.gov.pl) |
| http4s-circe | 0.23.30 | JSON response encoding |
| http4s-dsl | 0.23.30 | Route DSL |
| cats-effect | **3.5.7** | Async runtime — see version constraint note below |
| cats-core | **2.9.0** | Functional core |
| fs2-core / fs2-io | 3.10.2 | Streaming |
| circe-core/generic/parser | 0.14.10 | JSON codec |
| doobie-core/postgres/hikari | 1.0.0-RC4 | Database access |
| postgresql JDBC | 42.7.11 | PostgreSQL driver |
| Flyway | 12.8.0 | Schema migrations |
| logback-classic | 1.5.34 | Logging backend |
| log4cats-slf4j | 2.6.0 | Functional logging |
| Typesafe Config | 1.4.3 | Application config (`application.conf`) |
| ip4s-core | 3.4.0 | Host/port types |

### Critical version constraint

**Do not upgrade cats-effect past 3.5.7 or cats-core past 2.9.0** without re-verifying compilation under Bazel.

cats-core 2.12.0 introduced `TupleParallelSyntax` with a TASTy encoding that every Scala 3.x compiler version (through at least 3.8.x) fails to read. This causes a fatal compilation crash (`TupleParallelSyntax` in `cats.syntax`) when any library that transitively depends on cats-core 2.12+ is on the classpath. cats-effect 3.7.0 pulls in cats-core 2.13.0 and therefore triggers this crash. The 3.5.7 line stays on cats-core 2.9.0 and is safe.

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
| Backend JVM | `eclipse-temurin:21-jre-alpine` |
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

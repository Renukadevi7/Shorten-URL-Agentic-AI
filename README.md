# Agentic URL Shortener

Working prototype of an **agentic SDLC orchestration control plane** that turns a requirement into a reviewable engineering outcome for a URL shortener.

The system coordinates requirements → architecture → security review → implementation → testing → documentation → release readiness using an explicit stage DAG, metadata entry/exit gates, human approvals, retries/fallback/rollback, dynamic re-planning, policy guardrails, and audit metrics.

## Honest defaults

- **Default model is scripted** (`ScriptedModelClient`): offline-reproducible draft → compile → repair. It is **not** a live LLM.
- Optional live model: set `agentic.model.url` (non-local must be `https://`) and `AGENTIC_MODEL_API_KEY`.
- ChangeAgent output (`GeneratedAliasRules`) is compiled, checked in a **forked JVM**, then installed into the live shortener via `AliasRulesHolder`.

## Requirements

- JDK 17+
- Maven Wrapper (`./mvnw` / `mvnw.cmd`)
- API key via environment (**≥ 16 characters**, not a placeholder like `test-api-key`)

## Installation & run

```bash
export AGENTIC_API_KEY=your-local-secret-16+
# optional: export AGENTIC_RATE_LIMIT=120
./mvnw spring-boot:run
```

Windows (PowerShell):

```powershell
$env:AGENTIC_API_KEY = "your-local-secret-16+"
# optional: $env:AGENTIC_RATE_LIMIT = "120"
.\mvnw.cmd spring-boot:run
```

Open [http://127.0.0.1:43127](http://127.0.0.1:43127) and paste the **same** key into the UI (never embedded in HTML/JS).

Optional gitignored override: copy `application-local.properties.example` → `application-local.properties`.

## Tests

```bash
./mvnw test
```

## Scenarios

JSON DAGs live under `src/main/resources/orchestration/graphs/` (handlers by name; `produces`/`consumes` drive gates; `markProduces` needs no new Java).

| Scenario | Purpose |
| --- | --- |
| **greenfield** | Build shortener capabilities under a gated SDLC |
| **brownfield** | Impact analysis + analytics/reliability evolution |
| **ambiguous** | Provisional scope; **Mutate upstream** forces dynamic re-plan |

Fault injection (UI / API): target a **stage id** with a kind, e.g. `test` + `FAIL` (fallback recovery), or `impl:FAIL_AFTER_SNAPSHOT` (product rollback).

**Start orchestration** runs through ChangeAgent before the first approval gate (usually a few seconds; first JVM fork can be slower). Live run stays idle until that response returns.

## Security notes

- Blank / weak API keys fail startup; `allow-blank` is tests-only (not in main config)
- Metrics and config require `X-API-Key`
- Rate limit runs **before** auth, keyed by **client address**, with `Retry-After` on 429
- Public `GET /{code}` redirects skip reserved paths (`h2-console`, `api`, …)
- Alias create uses `persist()` (no lost-update race); clicks use one atomic `UPDATE`

## Core APIs

Mutating and orchestration/metrics/config calls need:

```http
X-API-Key: <AGENTIC_API_KEY>
```

### Orchestration

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/orchestration/runs` | Start a scenario run |
| `GET` | `/api/orchestration/runs` | List runs |
| `GET` | `/api/orchestration/runs/{id}` | Run detail + lineage |
| `POST` | `/api/orchestration/runs/{id}/approve` | Approve gated stage |
| `POST` | `/api/orchestration/runs/{id}/reject` | Reject / safe-stop |
| `POST` | `/api/orchestration/runs/{id}/resume` | Resume after safe-stop |
| `POST` | `/api/orchestration/runs/{id}/safe-stop` | Operator safe-stop |
| `POST` | `/api/orchestration/runs/{id}/fault` | Fault injection (`stageId` + `kind`) |
| `POST` | `/api/orchestration/runs/{id}/mutate-upstream` | Dynamic re-plan |
| `GET` | `/api/orchestration/metrics` | Reliability metrics (auth required) |

### URL shortener

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/urls` | Create (`url`, optional `customAlias`, `ttlSeconds`) |
| `GET` | `/{code}` | Public redirect |
| `DELETE` | `/api/urls/{code}` | Soft-delete |
| `GET` | `/api/urls/{code}/stats` | Click analytics |
| `GET` | `/api/urls/features` | Feature flags |

## Docs

- `docs/ARCHITECTURE.md` — component map
- `docs/FINAL_SUMMARY.md` — claims, evidence, scripted-model honesty, limitations

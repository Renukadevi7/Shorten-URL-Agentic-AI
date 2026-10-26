# Agentic URL Shortener — SDLC Orchestration Prototype

Working prototype for the Agentic Software Engineering assessment: an orchestration control plane that turns requirements into reviewable engineering outcomes for a **URL shortener**.

## Quick start

```bash
./mvnw spring-boot:run
```

Open http://127.0.0.1:43127

API key for mutating endpoints:

```http
X-API-Key: demo-api-key-change-me
```

## After git pull — force a clean UI reload

Your browser + Spring can keep old CSS. After pulling UI changes:

```bash
# 1) confirm you actually have the background markup
grep -n "bg-glow\|build-tag\|20261026b" src/main/resources/templates/index.html

# 2) stop any old Java process on 43127, then rebuild/run
./mvnw clean spring-boot:run
```

Then hard-refresh the browser: **Ctrl+Shift+R** (Windows) / **Cmd+Shift+R** (Mac).

You should see:
- a **blue animated top ribbon**
- **blue/teal glow + grid** behind the cards
- a pill: `UI build 20261026b · atmospheric background enabled`
- Live-run buttons including **Inject FAIL_TESTING** and **Mutate upstream (re-plan)**

If `grep` finds nothing, you pulled a different remote/branch than the one with the UI update.

## What was strengthened (feedback response)

1. **Parallel execution** — ready stages run on a thread pool; `CompletableFuture.allOf` sync barrier
2. **Gates** — `GatePolicy` validates artifacts/context (tests passed, architecture present, release policy)
3. **Retry / fallback / rollback / safe-stop** — retries, `test_fallback` edge, H2 snapshot restore, safe-stop; trigger via `/fault`
4. **Dynamic re-plan** — `/mutate-upstream` changes fingerprint and inserts `REPLAN` stage
5. **Lineage + metrics** — audit events + retries/rollbacks/latency/MTTR from real runs
6. **Policy guardrails** — security/compliance/change-control beyond simple string checks
7. **Shortener reliability** — expiry, delete, custom alias, rate limit, API auth, H2 persistence
8. **Tests** — stricter unit + integration tests including auth and failure-oriented orchestration cases
9. **Docs aligned** — `docs/ARCHITECTURE.md` matches real classes
10. **Final summary** — `docs/FINAL_SUMMARY.md`

## Core APIs

### Orchestration
- `POST /api/orchestration/runs`
- `POST /api/orchestration/runs/{id}/approve|reject|resume|safe-stop`
- `POST /api/orchestration/runs/{id}/fault` `{"type":"FAIL_TESTING","once":false}`
- `POST /api/orchestration/runs/{id}/mutate-upstream` `{"requirement":"..."}`
- `GET /api/orchestration/metrics`

### URL shortener
- `POST /api/urls` `{"url":"https://...","customAlias":"opt","ttlSeconds":3600}`
- `GET /{code}` public redirect
- `DELETE /api/urls/{code}`
- `GET /api/urls/{code}/stats`
- `GET /api/urls/features`

## Scenarios

- **greenfield** — build shortener capabilities under gated SDLC
- **brownfield** — impact analysis + analytics/reliability evolution
- **ambiguous** — start provisional; use mutate-upstream to force dynamic re-plan

## Docs

- `READ.md` — plan overview
- `docs/ARCHITECTURE.md` — components and control flow
- `docs/FINAL_SUMMARY.md` — rationale, evidence, risks, limitations
- `docs/assignment.pdf` — original assignment

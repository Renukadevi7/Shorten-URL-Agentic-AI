# Hiring Manager Walkthrough

Use this as your talk track. Map: **assignment ask → what we built → how to demo**.

## One-sentence pitch

This is an **agentic SDLC control plane** (not a chatbot wrapper): a DAG orchestrator with gates, parallel stages, retries/fallback/rollback, dynamic re-plan, policy guardrails, and audit metrics — producing a real **URL shortener** under human approval.

## Assignment (PDF) coverage

| PDF requirement | Where it lives |
| --- | --- |
| Requirement understanding / normalize | `StageExecutor.requirements`, run `normalizedProblem` |
| Task decomposition + dependencies | `StageGraph` / `ScenarioFactory` (greenfield, brownfield, ambiguous) |
| Brownfield impact reasoning | brownfield scenario + architecture impact map |
| **Workflow orchestration (critical)** | `OrchestratorService` + gates/parallel/fallback/replan/metrics |
| Engineering outputs (APIs/tests/docs) | shortener APIs + `src/test` + stage doc artifact |
| Validation / risk control | `GatePolicy`, `PolicyGuardrail`, snapshot rollback |
| Controlled autonomy | Implementation + Release human approval |
| Final engineering summary | `docs/FINAL_SUMMARY.md` |
| Architecture overview | `docs/ARCHITECTURE.md` |
| Three scenarios | UI chips + `ScenarioFactory` |
| Setup instructions | `README.md` |

## Feedback checklist (10/10) — what to say

### 1. Parallel execution + sync
**Say:** After Implementation approval, `test` and `docs` run on a thread pool; `CompletableFuture.allOf` waits before Release.
**Show:** Start greenfield → Approve impl → lineage shows `PARALLEL_WAVE` and both stages succeed before release gate.
**Code:** `OrchestratorService.advance`

### 2. Gates validate outputs
**Say:** Entry/exit gates check artifacts/context (`testsPassed`, architecture map, release checklist) — not only “deps done.”
**Show:** Release won’t enter without `testsPassed` + `docsReady`.
**Code:** `GatePolicy`

### 3. Retry / fallback / rollback / safe-stop (triggerable)
**Say:** Bounded retries → `test_fallback` edge → H2 `ProductSnapshot.restore` → safe-stop. Fault injection proves it.
**Show:** Before approving impl, click **Inject FAIL_TESTING** → Approve → lineage: `STAGE_FAILURE` → `RETRY` → `FALLBACK_ACTIVATED`.
**Also show:** **Safe stop**.
**Code:** `OrchestratorService.handleFailure`, `UrlShortenerService.restore`

### 4. Dynamic re-planning
**Say:** Re-plan is **not** a hard-coded clarification stage. Ambiguous starts like greenfield; **Mutate upstream** changes fingerprint and inserts `REPLAN`.
**Show:** Ambiguous run → **Mutate upstream (re-plan)** → lineage `UPSTREAM_CHANGED` / `DYNAMIC_REPLAN`.
**Code:** `OrchestratorService.mutateUpstream` / `dynamicReplan`

### 5. Lineage + metrics
**Say:** Every decision is an audit event; metrics (retries, rollbacks, latency, MTTR, success rate) come from real runs.
**Show:** Live run lineage panel + left-side Reliability metrics / `GET /api/orchestration/metrics`.
**Code:** `AuditLog`, `MetricsRegistry`

### 6. Policy guardrails
**Say:** Security (HTTPS / blocked hosts / dangerous schemes), compliance (PII / no disable-auth), change-control (tests+docs before release).
**Show:** Create URL to `http://localhost/...` → blocked. Release policy checks in gate.
**Code:** `PolicyGuardrail`

### 7. URL shortener reliability
**Say:** Alias, TTL expiry, soft delete, redirect analytics, API key, rate limit, H2 persistence.
**Show:** `POST /api/urls` with `customAlias` + `ttlSeconds`, then `GET /{code}`, `DELETE`, `stats`. Header `X-API-Key: demo-api-key-change-me`.

### 8. Tests
**Say:** Unit + integration cover alias/expiry/delete/snapshot-restore, auth 401, orchestration fault/replan paths.
**Show:** `./mvnw test` (or mention green CI locally).

### 9. Architecture doc aligned
**Say:** `docs/ARCHITECTURE.md` names the real classes (`StageGraph`, `GatePolicy`, `AuditLog`, snapshot rollback) — they exist in `src/main/java`.

### 10. Final summary
**Say:** `docs/FINAL_SUMMARY.md` has plan/rationale, evidence, risks, assumptions, limitations (PDF §4.8 / deliverables).

## 8-minute live demo script

1. Open http://127.0.0.1:43127 — confirm build tag `20261026b`.
2. **Greenfield** → Start → Approve impl → point at parallel test∥docs → Approve release → `COMPLETED`.
3. New greenfield → **Inject FAIL_TESTING** → Approve → show fallback lineage.
4. **Ambiguous** → Start → **Mutate upstream** → show `DYNAMIC_REPLAN`.
5. Curl or UI note: create short URL with alias/TTL; show redirect + delete.
6. Open `docs/ARCHITECTURE.md` + `docs/FINAL_SUMMARY.md` briefly.

## What README “What was strengthened” is for

Keep it. It is your **feedback response map** for reviewers skimming GitHub.  
For the hiring manager interview, lead with **orchestration story + live demo**, and use ARCHITECTURE / FINAL_SUMMARY as the written evidence — not the README bullet list alone.

## Bundle (Part 2 submission)

After confirming latest commit locally:

```bash
cd "/mnt/c/Users/jrenu/Workspace/Charles Sche Agentic AI projects"
git log --oneline -1    # expect 8d77a9b or later
git push github main
git bundle create Renukadevi.bundle --all
ls -lh Renukadevi.bundle
```

Attach **`Renukadevi.bundle`** (filename only in the email). Regenerate whenever `main` moves.

## Honest limitations (say these; builds trust)

- Stage “agents” are deterministic heuristics (no LLM) so evaluation focuses on governance.
- Orchestration run state is in-memory; product data is H2 file.
- Rate limit is single-node memory.
- Prototype autonomy boundaries: humans approve Implementation + Release.

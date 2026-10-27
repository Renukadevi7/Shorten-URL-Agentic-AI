# Architecture Overview

## Components (as implemented)

| Component | Class / resource | Responsibility |
| --- | --- | --- |
| Orchestrator | `OrchestratorService` | Advances DAG runs, parallel waves, sync barrier, approvals, fault injection, dynamic replan, MTTR clock |
| Stage graphs | `orchestration/graphs/*.json` + `StageGraphLoader` | Declarative stage metadata (deps, gates, fallback, approval) — **new stages of existing types need JSON only** |
| Gate policy | `GatePolicy` | Entry/exit gates validate required context/artifacts from stage metadata |
| Stage executor | `StageExecutor` | Handler registry by `StageType` |
| Change agent | `ChangeAgent` / `HeuristicChangeAgent` | produce **unified diff + OpenAPI spec** → apply → validate |
| Audit log | `AuditLog` | Append-only decision lineage |
| Policy guardrails | `PolicyGuardrail` | Security, compliance, change-control |
| Metrics | `MetricsRegistry` | success rate, retries, rollbacks, e2e latency; **MTTR = failure→recovery** |
| Product | `UrlShortenerService` + JPA/H2 | Persistent shortener (alias, TTL, delete, stats) |
| Auth / rate limit | `ApiKeyAuthFilter`, `RateLimitFilter` | `AGENTIC_API_KEY`; per-key RPM |

## Scenarios

| Scenario | Graph file | Intent |
| --- | --- | --- |
| **greenfield** | `orchestration/graphs/greenfield.json` | Build shortener under gated SDLC |
| **brownfield** | `orchestration/graphs/brownfield.json` | Analytics/reliability evolution on existing product |
| **ambiguous** | `orchestration/graphs/ambiguous.json` | Provisional scope; `mutate-upstream` inserts REPLAN |

Each graph includes a **SECURITY_REVIEW** stage (`sec`) added via JSON configuration (not hard-coded in the engine).

## Orchestration control flow

1. Start materializes the scenario JSON graph.
2. Ready stages (deps + entry context) run as a **parallel wave**.
3. `CompletableFuture.allOf(...)` is the **synchronization barrier**.
4. Exit gates check required artifacts/context; high-impact stages require human approval.
5. Implementation uses ChangeAgent: **unifiedDiff + OpenAPI** recorded on the stage, then apply/validate (including expiry check).
6. On failure: MTTR clock → bounded retries → fallback (if configured) → snapshot rollback → safe-stop. Recovery records MTTR (not full-run latency).
7. `mutate-upstream` inserts REPLAN and rewires the first `ARCHITECTURE` stage by **type**, not by hard-coded id.
8. `fault` injects `FAIL_TESTING` / `FAIL_IMPLEMENTATION` for reliability demos.

## Parallelism example

After Implementation is approved: `test` ∥ `docs` share a ready-set wave on the `orch-parallel` pool; Release waits for deps + `testsPassed`/`docsReady`.

## Metrics definitions

| Metric | Definition |
| --- | --- |
| End-to-end latency | `endedAt - startedAt` for a finished run |
| MTTR | Time from first failure detection (`failureStartedAtMs`) until successful recovery (retry, fallback, or resume) |
| Retry count | Count of bounded retry attempts |
| Rollback count | Count of product snapshot restores |
| Success rate | `completed / (completed + failed_or_safe_stopped)` |

## Security

- No default API key in committed config; set `AGENTIC_API_KEY` (or gitignored local properties).
- H2 console disabled in committed config.
- Confidential assignment PDF removed from the working tree; see FINAL_SUMMARY for history assessment.

## Risks & mitigation

| Risk | Mitigation |
| --- | --- |
| Unsafe URLs | scheme/host policy + local/loopback block |
| Unreviewed release | approval + release policy gate + SECURITY_REVIEW stage |
| Silent test failure | exit gate requires passed artifacts |
| Partial mutation | pre-impl snapshot / restore rollback |
| Secret leakage | env-based API key; no committed defaults |

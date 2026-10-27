# Architecture Overview

## Components (as implemented)

| Component | Class | Responsibility |
| --- | --- | --- |
| Orchestrator | `OrchestratorService` | Advances DAG runs, parallel waves, sync barrier, approvals, fault injection, dynamic replan, MTTR recovery clock |
| Stage graph | `StageGraph` + `ScenarioFactory` | Explicit stage nodes with dependency/fallback edges and declarative gate metadata |
| Gate policy | `GatePolicy` | Entry/exit gates validate **required context/artifacts** from stage metadata |
| Stage executor | `StageExecutor` | Handler registry by `StageType` (no stage-id special cases) |
| Change agent | `ChangeAgent` / `HeuristicChangeAgent` | produce → apply → validate change artifacts for implementation |
| Audit log | `AuditLog` | Append-only decision lineage on each run |
| Policy guardrails | `PolicyGuardrail` | Security, compliance, change-control checks |
| Metrics | `MetricsRegistry` | success rate, retries, rollbacks, e2e latency; **MTTR = failure→recovery** |
| Product | `UrlShortenerService` + JPA/H2 | Persistent shortener with expiry/delete/alias |
| Auth / rate limit | `ApiKeyAuthFilter`, `RateLimitFilter` | API key from `AGENTIC_API_KEY`; per-key RPM limit |

## Orchestration control flow

1. `POST /api/orchestration/runs` materializes a scenario graph with declarative stage metadata.
2. Ready stages (deps satisfied + entry context) execute as a **parallel wave**.
3. `CompletableFuture.allOf(...)` is the **synchronization barrier** before the next wave.
4. Exit gates validate required artifacts/context; high-impact stages pause for human approval.
5. On failure: MTTR clock starts → bounded retries → fallback edge (if configured) → snapshot rollback → safe-stop. Recovery (retry/fallback/resume) records MTTR.
6. Implementation uses `ChangeAgent.produce/apply/validate` rather than inline flag toggles.
7. `POST .../mutate-upstream` inserts a **REPLAN** stage and invalidates transitive downstream stages.
8. `POST .../fault` injects `FAIL_TESTING` / `FAIL_IMPLEMENTATION` for reliability demos.

## Parallelism example

After Implementation is approved:

- `test` and `docs` become ready together
- both are submitted to the `orch-parallel` thread pool
- Release cannot enter until deps + entry context (`testsPassed`, `docsReady`) are satisfied

## Rollback behavior

Before a change is applied, `productSnapshotBeforeImpl` is stored. Rollback restores analytics flags and URL rows via `UrlShortenerService.restore`.

## Security configuration

- No default API key is committed. Set `AGENTIC_API_KEY` or local `application-local.properties` (gitignored).
- H2 console is disabled in committed config.

## Risks & validation

| Risk | Mitigation |
| --- | --- |
| Unsafe URLs | scheme/host policy + local/loopback block |
| Unreviewed release | approval + release policy gate |
| Silent test failure | exit gate requires passed artifacts / context |
| Partial product mutation | snapshot/restore rollback |
| Secret leakage | env-based API key; no committed defaults |

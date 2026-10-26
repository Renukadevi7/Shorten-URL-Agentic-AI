# Architecture Overview

## Components (as implemented)

| Component | Class | Responsibility |
| --- | --- | --- |
| Orchestrator | `OrchestratorService` | Advances DAG runs, parallel waves, sync barrier, approvals, fault injection, dynamic replan |
| Stage graph | `StageGraph` | Explicit stage nodes, dependency edges, fallback edges |
| Gate policy | `GatePolicy` | Entry/exit gates that validate **outputs** (artifacts/context), not only status flags |
| Stage executor | `StageExecutor` | Performs stage work; writes artifacts + cross-stage decisions |
| Audit log | `AuditLog` | Append-only decision lineage on each run |
| Policy guardrails | `PolicyGuardrail` | Security, compliance, change-control checks |
| Metrics | `MetricsRegistry` | success rate, retries, rollbacks, e2e latency, MTTR from real runs |
| Product | `UrlShortenerService` + JPA/H2 | Persistent shortener with expiry/delete/alias |
| Auth / rate limit | `ApiKeyAuthFilter`, `RateLimitFilter` | API key auth + per-key RPM limit |

## Orchestration control flow

1. `POST /api/orchestration/runs` materializes a `StageGraph` for the scenario.
2. Ready stages (deps satisfied + entry gate output checks) execute as a **parallel wave**.
3. `CompletableFuture.allOf(...)` is the **synchronization barrier** before the next wave.
4. Exit gates validate artifacts/context; high-impact stages pause for human approval.
5. On failure: bounded retries → **fallback edge** (if configured) → **rollback restore** of `ProductSnapshot` → safe-stop.
6. `POST .../mutate-upstream` changes upstream fingerprint and inserts a **REPLAN** stage (dynamic, not hard-coded clarification).
7. `POST .../fault` injects `FAIL_TESTING` / `FAIL_IMPLEMENTATION` to demonstrate retry/fallback/rollback.

## Parallelism example

After Implementation is approved:

- `test` and `docs` become ready together
- both are submitted to a thread pool
- Release cannot enter until both completed (deps + entry gate requiring `testsPassed` and `docsReady`)

## Rollback behavior

Before Implementation mutates the product, a `productSnapshotBeforeImpl` is stored. Rollback restores analytics flags and URL rows in H2 via `UrlShortenerService.restore`.

## Key decisions

- Heuristic stage agents (deterministic) so evaluation focuses on governance/orchestration.
- H2 file DB for durable demo storage without external infra.
- API key header for mutating APIs; public redirect remains open.

## Risks & validation

| Risk | Mitigation |
| --- | --- |
| Unsafe URLs | scheme/host policy + local/loopback block |
| Unreviewed release | approval + release policy gate |
| Silent test failure | exit gate requires `passed=true` artifact |
| Partial product mutation | snapshot/restore rollback |

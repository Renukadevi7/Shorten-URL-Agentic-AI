# Architecture Overview

## Components (verified in source)

| Component | Class / resource | Responsibility |
| --- | --- | --- |
| Orchestrator | `OrchestratorService` | Parallel ready-set waves, sync barrier, approvals, faults, dynamic replan, MTTR clock |
| Stage graphs | `orchestration/graphs/*.json` + `StageGraphLoader` | Metadata: deps, entry/exit context/artifacts, fallback, approval, `activationOnly`, `completesRun` |
| Gate policy | `GatePolicy` | Metadata-driven entry/exit validation (no stage-id switches) |
| Stage executor | `StageExecutor` | Handler registry by `StageType` |
| Change agent | `ChangeAgent` / `HeuristicChangeAgent` | produce **unifiedDiff + OpenAPI** → apply → validate |
| Policy | `PolicyGuardrail` + `PolicyDecision` | Explicit ALLOW/BLOCK decisions with audit bundle |
| Audit / metrics | `AuditLog`, `MetricsRegistry` | Lineage; MTTR only on recovery (never full-run latency as MTTR) |
| Product | `UrlShortenerService` + H2 | Alias, TTL/expiry, delete, stats, snapshot/restore |
| Auth | `ApiKeyAuthFilter` | `AGENTIC_API_KEY` via env; **key is not embedded in HTML/JS** |

## Scenarios

| Scenario | Graph | What it demonstrates |
| --- | --- | --- |
| greenfield | `graphs/greenfield.json` | Interpret → decompose → ChangeAgent change → gates → approvals |
| brownfield | `graphs/brownfield.json` | Impact analysis + analytics/reliability evolution |
| ambiguous | `graphs/ambiguous.json` | Provisional scope; `mutate-upstream` updates requirement and **derives** replan text from it |

`SECURITY_REVIEW` (`sec`) is present only in JSON — proof that stages are config-driven.

## Metrics

| Metric | Definition |
| --- | --- |
| End-to-end latency | Full run duration |
| MTTR | Failure detection → successful recovery only (`onRecovery`). Failed/safe-stopped runs do **not** record latency as MTTR |
| Retries / rollbacks | Actual attempt counters |
| Success rate | completed / (completed + failed_or_safe_stopped) |

## Security hygiene

- No committed default API key; H2 console disabled
- UI never receives the API key from the server
- Confidential PDF removed from tree; history purge assessed in FINAL_SUMMARY

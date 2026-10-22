# Architecture Overview

## Components

| Component | Responsibility |
| --- | --- |
| `OrchestratorService` | Executes DAG runs, gates, retries, rollback, re-plan |
| `StageGraph` | Explicit dependency graph with parallel edges |
| `GatePolicy` | Entry/exit predicates + human approval requirements |
| `PolicyGuardrail` | Security/compliance/change-control checks |
| `AuditLog` | Append-only decision lineage |
| `MetricsRegistry` | Success, retry, rollback, latency, MTTR |
| `UrlShortenerService` | Product under construction / evolution |
| Scenario loaders | Greenfield / brownfield / ambiguous graphs |

## Control flow

1. Client starts a scenario → orchestrator creates a `Run` with context.
2. Ready stages (deps satisfied + entry gate OK) execute — sequential or parallel.
3. Exit gate may require **human approval**; run pauses in `AWAITING_APPROVAL`.
4. On failure: bounded retry → fallback stage path → rollback snapshot → safe-stop.
5. If ambiguous inputs detected, orchestrator **re-plans** by inserting a Clarification stage.
6. Completion produces artifacts (API notes, test report, docs snippet, release checklist).

## Key decisions

- **Java 17 + Spring Boot 4** for a production-familiar stack matching prior preference.
- **In-memory** product store to keep the prototype self-contained.
- **Heuristic stage agents** to prove governance/orchestration without API-key coupling.
- **Feature flags** on the shortener to simulate brownfield evolution in one process.

## Risks & validation

| Risk | Mitigation |
| --- | --- |
| Agent overreach | Approval gates on implementation/release |
| Open redirect / unsafe URLs | URL policy guardrail |
| Silent partial failure | Audit lineage + metrics |
| Ambiguous requirements | Clarification stage + re-plan |

## Assumptions

- Single-node demo; no distributed consensus.
- Human operator available for approval checkpoints.
- Assignment evaluates orchestration depth more than LLM cleverness.

## How assignment criteria map

| Criterion | Where shown |
| --- | --- |
| Requirement understanding | Scenario normalizers + clarification |
| Task decomposition | `StageGraph` per scenario |
| Workflow orchestration | `OrchestratorService` |
| Engineering outputs | Shortener APIs + stage artifacts |
| Validation / risk | Guardrails + tests |
| Controlled autonomy | Approval API/UI |
| Final summary | This doc + README |

# Final Engineering Summary

## Plan and rationale

Build an **agentic SDLC control plane** that produces a real **URL shortener** outcome. The hiring/evaluation signal is orchestration quality: dependency graph, validating gates, concurrent stages with sync, fallback/rollback, dynamic re-plan, lineage, and metrics — under human approval boundaries.

## Artifacts produced

- Runnable Spring Boot app (`./mvnw spring-boot:run`) on port `43127`
- Orchestration APIs including fault injection and upstream mutation
- URL APIs: create (alias/TTL), redirect, delete, stats; H2 persistence; API key + rate limit
- Scenarios: greenfield, brownfield, ambiguous
- Unit + integration tests under `src/test/java`
- Architecture doc aligned to concrete classes

## Validation evidence

- Entry/exit gates fail closed when artifacts/context are missing (`GatePolicy`)
- Parallel wave logged as `PARALLEL_WAVE` with concurrent stage entry thread names
- `FAIL_TESTING` fault exhausts retries then activates `test_fallback`
- Rollback restores H2 product rows from `productSnapshotBeforeImpl`
- `mutate-upstream` emits `UPSTREAM_CHANGED` / `DYNAMIC_REPLAN` and inserts a `REPLAN` stage
- Metrics endpoint exposes retries/rollbacks/latency/MTTR derived from completed/failed runs
- Integration tests assert auth rejection, alias create, delete, orchestration start

## Risks / trade-offs

- Stage agents are heuristic (no LLM) — deliberate for determinism and offline evaluation
- In-process orchestration state (runs map) is not clustered; product data is in H2 file
- Rate limiter is memory-local (sufficient for prototype, not multi-node)

## Assumptions

- Operator can supply `X-API-Key: demo-api-key-change-me`
- Human approvals available for Implementation and Release
- Demo hosts use public HTTPS URLs (localhost targets blocked)

## Limitations

- No IdP/SSO; API key only
- No distributed transaction across orchestration + product beyond snapshot restore
- Brownfield impact analysis is structured metadata, not static code analysis
- UI is a control-plane dashboard, not a full design portal

# Final Engineering Summary

## Plan and rationale

Build an **agentic SDLC control plane** that produces a real **URL shortener** outcome. Evaluation signal is orchestration quality: dependency graph, metadata-driven gates, concurrent stages with sync, ChangeAgent produce/apply/validate, fallback/rollback, dynamic re-plan, lineage, and metrics — under human approval boundaries.

## Artifacts produced

- Runnable Spring Boot app (`./mvnw spring-boot:run`) on port `43127`
- Orchestration APIs including fault injection and upstream mutation
- URL APIs: create (alias/TTL), redirect, delete, stats; H2 persistence; API key + rate limit
- Scenarios: greenfield, brownfield, ambiguous
- Unit + integration tests (auth, rate-limit, concurrency overlap, failure/fallback/MTTR)
- Architecture doc aligned to concrete classes

## Validation evidence

- Entry/exit gates driven by stage metadata (required context/artifacts)
- Parallel wave uses `CompletableFuture.allOf`; tests assert overlapping `orch-parallel` stage entry
- `FAIL_TESTING` exhausts retries then activates fallback; MTTR samples increase on recovery
- Implementation lineage includes `AGENT_PRODUCE` / `AGENT_APPLY` / `AGENT_VALIDATE`
- `mutate-upstream` emits `UPSTREAM_CHANGED` / `DYNAMIC_REPLAN`
- Metrics expose retries/rollbacks/latency and MTTR from failure→recovery (not full-run latency)
- Integration tests cover missing/wrong API key (401) and rate-limit (429)

## Risks / trade-offs

- Default ChangeAgent is deterministic (pluggable for LLM) so demos stay offline-reproducible
- In-process orchestration state is not clustered; product data is H2 file
- Rate limiter is memory-local (prototype scope)

## Assumptions

- Operator supplies `AGENTIC_API_KEY` (or gitignored local properties)
- Human approvals available for Implementation and Release
- Demo hosts use public HTTPS URLs (localhost targets blocked)

## Limitations

- No IdP/SSO; API key only
- Heuristic ChangeAgent is not a hosted LLM (contract is LLM-ready)
- No distributed transaction across orchestration + product beyond snapshot restore
- UI is a control-plane dashboard, not a full design portal

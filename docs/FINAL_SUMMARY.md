# Final Engineering Summary

## Design decisions

- Treat orchestration as the product: declarative JSON stage graphs, metadata-driven gates, pluggable `ChangeAgent`.
- Deterministic `HeuristicChangeAgent` produces a **unified source diff** and **OpenAPI 3** spec, applies product mutations under snapshot, and validates (HTTPS, resolve, expiry, policy, artifact presence). An LLM agent can replace the bean without engine changes.
- Graphs live under `src/main/resources/orchestration/graphs/`; the `SECURITY_REVIEW` stage was added via JSON to prove the engine does not hard-code stage ids.
- MTTR is measured as failure→recovery; end-to-end latency remains a separate full-run metric.

## Artifacts produced

- Spring Boot app on port `43127` (`./mvnw spring-boot:run` with `AGENTIC_API_KEY`)
- Orchestration + URL APIs (alias, TTL, delete, stats, H2 file persistence, auth, rate limit)
- Scenarios: greenfield, brownfield, ambiguous
- Tests: ChangeAgent artifacts, concurrency overlap, fallback/MTTR, rollback/safe-stop, auth 401, rate-limit 429, expiry
- `docs/ARCHITECTURE.md`, this summary, README setup

## Validation evidence

| Requirement | Evidence |
| --- | --- |
| Real ChangeAgent | Lineage `AGENT_PRODUCE`/`APPLY`/`VALIDATE`; stage artifacts include `unifiedDiff` + `apiSpecification` |
| Generic orchestration | `sec` SECURITY_REVIEW stage only in JSON; dynamic replan finds ARCHITECTURE by type |
| Concurrent sync | `PARALLEL_WAVE` + `orch-parallel` threads for test∥docs |
| Recovery | `FAIL_TESTING` → retries/fallback or ROLLBACK + SAFE_STOPPED; `RECOVERY` carries `mttrMs` |
| Metrics | MTTR samples increase on recovery; latency ≥ MTTR; documented definitions |
| Security | Unauthorized → 401; H2 console off; no committed API key; PDF removed from tree |
| URL features | alias, TTL/expiry, delete, auth, rate limit, H2 |

## Risks / trade-offs

- Default agent is deterministic (offline-reproducible) rather than a hosted LLM.
- In-process orchestration state; product data is H2 file.
- UI may bootstrap the runtime API key locally for demo DX (value still from env, not git).

## Assumptions

- Operator sets `AGENTIC_API_KEY` and can approve Implementation / Release.
- Demo hosts use public HTTPS URLs.

## Limitations

- No IdP/SSO; API key only.
- New *stage types* still need a handler registration; new *stages of existing types* are JSON-only.
- Not a clustered orchestrator.

## Confidential PDF / git history assessment

- `docs/assignment.pdf` is **removed from the current tree** (deleted in commit history as a normal file delete).
- Blobs may still exist in older commits. If the public GitHub remote ever contained that PDF, treat history as leaked: rotate any secrets that appeared in the PDF, and rewrite history with `git filter-repo` (or BFG) before final public promotion — then force-push and ask collaborators to re-clone.
- This Origin-backed working copy should not republish the PDF; do not restore it.

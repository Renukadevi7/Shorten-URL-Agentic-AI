# Final Engineering Summary

## Plan

Build an agentic SDLC control plane where orchestration quality is the differentiator: declarative graphs, metadata gates, pluggable ChangeAgent (diff + OpenAPI), concurrent waves, recovery, policy allow/block audit, and honest metrics.

## Artifacts

- Runnable Spring Boot app (`AGENTIC_API_KEY` required; port `43127`)
- JSON stage graphs for greenfield / brownfield / ambiguous
- Tests that assert fallback recovery, product rollback, overlapping stage windows, MTTR≠latency-as-MTTR, auth 401, rate-limit 429, expiry, ChangeAgent artifacts, requirement-derived replan

## Validation evidence (from `./mvnw test`)

| Claim | Test / proof |
| --- | --- |
| ChangeAgent diff + OpenAPI | `implementationUsesChangeAgentDiffAndOpenApiArtifacts` |
| Config-only stage | `graphJsonAddsSecurityStageWithoutEngineHardCodes` (`sec` in JSON) |
| Concurrent overlap | `parallelStagesHaveOverlappingExecutionWindows` |
| Fallback recovery | `faultInjectionRequiresSuccessfulFallbackRecovery` (requires FALLBACK + RECOVERY + skipped primary) |
| Real rollback of DB state | `postSnapshotImplFaultRollsBackProductData` (`priorurl` restored) |
| MTTR on recovery only | recovery event `mttrMs`; `onFail` records latency only |
| Ambiguous replan from new req | `ambiguousMutateUpstreamDerivesReplanFromNewRequirement` |
| Auth / rate limit | `ApiIntegrationTest` |
| Policy allow/block | `PolicyGuardrailTest` + `POLICY_EVAL` lineage |

## Risks / trade-offs

- Default `ScriptedModelClient` is a deterministic stand-in (not a live LLM); swap via `agentic.model.url` → `HttpModelClient`
- In-memory rate limiter (single-instance prototype)
- Orchestration state is in-process

## Assumptions / limitations

- Operator pastes API key into UI (not shipped in browser)
- Human approvals for implementation and release
- New *capabilities* register one named handler once; new stages of existing handlers (and config-only `markProduces` stages) are JSON-only
- Gate flags (`architectureReady`, `testsPassed`, …) are declared in graph `produces` / `consumes`, not hard-coded in replan/executor

## Confidential PDF history

- Removed from working tree.
- Older commits may still contain `docs/assignment.pdf` blobs. Before public promotion, run history rewrite (`git filter-repo --path docs/assignment.pdf --invert-paths`) and force-push; ask collaborators to re-clone.

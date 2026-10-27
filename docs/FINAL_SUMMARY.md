# Final Engineering Summary

## Plan

Build an agentic SDLC control plane where orchestration quality is the differentiator: declarative graphs, metadata gates, pluggable ChangeAgent (diff + OpenAPI), concurrent waves, recovery, policy allow/block audit, and honest metrics.

## What the default model is (and is not)

- **Default model client is scripted.** `ScriptedModelClient` is a deterministic stand-in: draft 1 is intentionally broken (missing semicolon) so the repair loop is exercised; draft 2+ returns valid `GeneratedAliasRules` source. **No live LLM is called** unless you set `agentic.model.url`.
- **Optional live client:** `HttpModelClient` (Bearer from `AGENTIC_MODEL_API_KEY` / `agentic.model.api-key`; non-local URLs must be `https://`).
- **What has been run here:** `./mvnw test` (unit/integration suite) and local `spring-boot:run` demos against the scripted client.
- **What has not been run / claimed:** end-to-end calls against a production LLM provider; load/chaos beyond the in-suite fault-injection paths; multi-node rate limiting.

## Artifacts

- Runnable Spring Boot app (`AGENTIC_API_KEY` required; port `43127`)
- JSON stage graphs for greenfield / brownfield / ambiguous
- ChangeAgent loop: policy scan → javac → **forked-JVM** AliasRules cases (from requirement TSV) → repair → install into `AliasRulesHolder`
- Tests for fallback recovery, product rollback, overlapping stages, MTTR≠full-run-latency, auth, rate-limit-before-auth, weak keys, ChangeAgent drafts, replan

## Validation evidence (from `./mvnw test`)

| Claim | Test / proof |
| --- | --- |
| ChangeAgent draft/compile/repair + scripted client | `implementationDraftCompileRepairProducesRealDiff` |
| AliasRules installed into product | `installedAliasRulesAffectProductAliasValidation` |
| Gate flags from JSON `produces` | `gateFlagsComeFromProducesMetadataNotHardCodedHandlerPuts` |
| Config-only / JSON security stage | `graphJsonAddsSecurityStageWithoutEngineHardCodes` |
| Concurrent overlap | `parallelStagesHaveOverlappingExecutionWindows` |
| Fallback + MTTR | `stageIdFaultTriggersFallbackRecoveryAndRecordsMttrValue` |
| Product rollback | `postSnapshotImplFaultRollsBackProductData` |
| Ambiguous replan | `ambiguousMutateUpstreamDerivesReplanFromNewRequirement` |
| Auth / rate-limit-before-auth / reserved paths | `ApiIntegrationTest`, `FilterConfigOrderTest` |
| Weak / blank key reject | `ApiKeyStartupValidatorTest` |
| Draft policy + case materializer | `DraftPolicyScannerTest`, `AliasCaseMaterializerTest` |

## Risks / trade-offs

- Scripted default model keeps demos offline-reproducible; live model path is opt-in and lightly integrated
- In-memory rate limiter (single-instance prototype)
- Orchestration state is in-process
- Many early commits were authored as `cursoragent@cursor.com`; recent commits use `Renukadevi <j.renukadevi7@gmail.com>`. History was not rewritten for this submission

## Assumptions / limitations

- Operator pastes API key into UI (not shipped in browser)
- Human approvals for implementation and release
- New *capabilities* register one named handler once; new stages of existing handlers (and config-only `markProduces` stages) are JSON-only
- Gate flags are declared in graph `produces` / `consumes`, not hard-coded in replan/executor
- Draft policy is static pattern scanning (not a full sandbox / seccomp)

## Confidential PDF history

- `docs/assignment.pdf` and the old committed default API key are **not** in the current tree.
- Older commits may still contain PDF blobs. Before public promotion, run history rewrite (`git filter-repo --path docs/assignment.pdf --invert-paths`) and force-push; ask collaborators to re-clone.

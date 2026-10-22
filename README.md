# Agentic URL Shortener — SDLC Orchestration Prototype

Interview assignment prototype: an **agentic software-engineering system** that turns a requirement into a reviewable engineering outcome, demonstrated by building and evolving a **URL shortener**.

> This replaces the earlier generic “agent playground” demo. Scope comes from `docs/assignment.pdf`.

## What we are going to do

| Phase | Goal |
| --- | --- |
| 1. Requirement understanding | Normalize greenfield / brownfield / ambiguous intents into engineering problems |
| 2. Task decomposition | Build an explicit **dependency graph** of SDLC stages with sequencing & parallelism |
| 3. Agentic orchestration | Run a **stateful** orchestrator with entry/exit gates, approvals, retries, rollback, safe-stop |
| 4. Engineering outputs | Produce runnable URL-shortener APIs, tests, and docs as stage artifacts |
| 5. Validation & governance | Policy guardrails, audit trail, decision lineage, reliability metrics |
| 6. Controlled autonomy | Agents execute; humans approve high-impact actions |
| 7. Final summary | Plan, artifacts, risks, assumptions, limitations |

## Architecture (target)

```
┌─────────────────────────────────────────────────────────────┐
│                     Control Plane (UI + API)                │
│  Start scenario · Approve gates · Inspect lineage/metrics   │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│              Agentic Orchestration Engine                   │
│  Dependency graph · Gates · Parallel sync · Re-plan         │
│  Retries / fallback / rollback / safe-stop                  │
│  Policy guardrails · Audit log · Decision lineage           │
└──────────────┬───────────────────────────────┬──────────────┘
               │                               │
               ▼                               ▼
┌──────────────────────────┐     ┌────────────────────────────┐
│   Stage Agents (SDLC)    │     │  URL Shortener Product     │
│  Requirements            │     │  Create / Redirect / Stats │
│  Architecture            │     │  Analytics · Reliability   │
│  Implementation          │     │  In-memory store (demo)    │
│  Testing                 │     └────────────────────────────┘
│  Documentation           │
│  Release readiness       │
└──────────────────────────┘
```

### Orchestration model

- **Stages** form a DAG (not a single linear script).
- **Entry/exit gates** must pass before entering/leaving a stage.
- **Parallel paths** (e.g. Testing ∥ Documentation) synchronize before Release.
- **Human approval** required for high-impact actions (e.g. IMPLEMENTATION exit, RELEASE).
- **Bounded retries** with fallback; **rollback** restores prior artifact snapshot; **safe-stop** freezes the run.
- **Policy guardrails**: block unsafe URLs, require HTTPS for production-like releases, deny unapproved schema changes.
- **Observability**: every decision is recorded; metrics include success rate, retries, rollbacks, MTTR, e2e latency.

### Three scenarios (assignment deliverable)

1. **Greenfield** — “Build a URL shortener with create + redirect APIs.”
2. **Brownfield** — “Add click analytics and basic reliability to the existing shortener.”
3. **Ambiguous** — “Make links better for leadership demo next week.” (forces clarification + re-plan)

## Run locally

**Requirements:** Java 17+, Maven wrapper included.

```bash
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

Open **http://127.0.0.1:43127**

### Key APIs

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | Orchestration dashboard |
| POST | `/api/orchestration/runs` | Start a scenario run |
| GET | `/api/orchestration/runs/{id}` | Run status + lineage |
| POST | `/api/orchestration/runs/{id}/approve` | Human approval gate |
| POST | `/api/orchestration/runs/{id}/reject` | Reject / safe-stop path |
| POST | `/api/orchestration/runs/{id}/resume` | Resume after stop |
| GET | `/api/orchestration/metrics` | Reliability metrics |
| POST | `/api/urls` | Create short URL `{ "url": "https://..." }` |
| GET | `/{code}` | Redirect |
| GET | `/api/urls/{code}/stats` | Click analytics |

## Project layout

```
src/main/java/com/agentic/
  shortener/         # URL shortener product
  orchestration/     # DAG engine, gates, policies, audit, metrics
  scenarios/         # greenfield / brownfield / ambiguous graphs
  web/               # REST + Thymeleaf dashboard
docs/
  assignment.pdf     # Source interview assignment
  ARCHITECTURE.md    # Deeper design notes
```

## Testing approach

- Unit tests for shortener + orchestration transitions
- Scenario smoke: start run → approve gates → completed artifacts
- Manual validation via dashboard

## Limitations & trade-offs

- Agents are **deterministic stage executors** (heuristic), not LLM-backed — focus is orchestration/governance fidelity.
- Storage is **in-memory** (resets on restart) for demo speed.
- Approvals are API/UI checkpoints, not SSO/IdP-integrated.
- “Brownfield” mutates the live in-process shortener feature flags rather than a separate git worktree.

## Final engineering summary (living)

See `docs/ARCHITECTURE.md` for decisions, risks, assumptions, and how each assignment criterion is demonstrated.

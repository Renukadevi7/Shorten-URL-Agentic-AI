# What we are going to do

Based on `docs/assignment.pdf` (Schwab: Agentic Proficient Software Engineer — URL Shortener).

## Goal

Build a **working prototype** of an **agentic software-engineering system** that:

1. Understands a requirement (greenfield / brownfield / ambiguous)
2. Decomposes it into an SDLC **dependency graph**
3. Orchestrates multi-step execution with **gates, approvals, retries, rollback, safe-stop**
4. Produces engineering outputs for a **URL shortener** (APIs, analytics, reliability)
5. Validates with guardrails, audit lineage, and reliability metrics
6. Keeps **humans in control** of high-impact actions

## Not in scope of the old demo

The previous generic “Agentic playground” (mock chat tools) was **not** the assignment. This project replaces it.

## Deliverables we will ship

- Runnable Spring Boot prototype (URL shortener + orchestration dashboard)
- Architecture overview (`docs/ARCHITECTURE.md` + `README.md`)
- Three scenarios: greenfield, brownfield, ambiguous
- Setup / run instructions
- Tests, limitations, trade-offs

## How to run

```bash
./mvnw spring-boot:run
```

Open http://127.0.0.1:43127

Full details: see `README.md`.

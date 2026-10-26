# Agentic URL Shortener

Working prototype of an **agentic SDLC orchestration control plane** that turns a requirement into a reviewable engineering outcome for a URL shortener.

The system coordinates requirements → architecture → implementation → testing → documentation → release readiness using an explicit stage DAG, entry/exit gates, human approvals, retries/fallback/rollback, dynamic re-planning, policy guardrails, and audit metrics.

## Requirements

- JDK 17+
- Maven Wrapper included (`./mvnw` / `mvnw.cmd`)

## Installation & run

```bash
git clone <repository-url>
cd <repository-directory>

# Ensure JAVA_HOME points at a JDK 17+ installation
java -version

./mvnw clean spring-boot:run
```

On Windows (Command Prompt / PowerShell):

```bat
mvnw.cmd clean spring-boot:run
```

Open [http://127.0.0.1:43127](http://127.0.0.1:43127)

Mutating APIs require:

```http
X-API-Key: demo-api-key-change-me
```

## Tests

```bash
./mvnw test
```

## Scenarios

| Scenario | Purpose |
| --- | --- |
| **greenfield** | Build shortener capabilities under a gated SDLC |
| **brownfield** | Impact analysis and analytics/reliability evolution |
| **ambiguous** | Provisional scope; use mutate-upstream to force dynamic re-plan |

## Core APIs

### Orchestration

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/orchestration/runs` | Start a scenario run |
| `GET` | `/api/orchestration/runs` | List runs |
| `GET` | `/api/orchestration/runs/{id}` | Get run detail + lineage |
| `POST` | `/api/orchestration/runs/{id}/approve` | Approve gated stage |
| `POST` | `/api/orchestration/runs/{id}/reject` | Reject / safe-stop |
| `POST` | `/api/orchestration/runs/{id}/resume` | Resume after safe-stop |
| `POST` | `/api/orchestration/runs/{id}/safe-stop` | Operator safe-stop |
| `POST` | `/api/orchestration/runs/{id}/fault` | Fault injection (`FAIL_TESTING`) |
| `POST` | `/api/orchestration/runs/{id}/mutate-upstream` | Trigger dynamic re-plan |
| `GET` | `/api/orchestration/metrics` | Reliability metrics |

### URL shortener

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/urls` | Create short URL (`url`, optional `customAlias`, `ttlSeconds`) |
| `GET` | `/{code}` | Public redirect |
| `DELETE` | `/api/urls/{code}` | Soft-delete |
| `GET` | `/api/urls/{code}/stats` | Click analytics |
| `GET` | `/api/urls/features` | Feature flags |

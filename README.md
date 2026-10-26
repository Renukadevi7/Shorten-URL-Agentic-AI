# Agentic URL Shortener — SDLC Orchestration Prototype

Working prototype for the Agentic Software Engineering assessment: an orchestration control plane that turns requirements into reviewable engineering outcomes for a **URL shortener**.

## Quick start

Requires **JDK 17+** and a correct `JAVA_HOME` (especially in WSL).

```bash
# WSL: find and export Java before Maven
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
echo "$JAVA_HOME"
java -version
./mvnw spring-boot:run
```

Open http://127.0.0.1:43127

### Fix: `JAVA_HOME environment variable is not defined correctly`

In **WSL** run these in order:

```bash
# 1) Is java installed?
which java
java -version

# 2) Common install locations
ls /usr/lib/jvm 2>/dev/null
ls "$HOME/.sdkman/candidates/java" 2>/dev/null

# 3a) If Java exists, set JAVA_HOME for this shell
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
export PATH="$JAVA_HOME/bin:$PATH"

# 3b) If java is missing, install Temurin 17 then retry 3a
sudo apt update
sudo apt install -y wget apt-transport-https
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo tee /etc/apt/trusted.gpg.d/adoptium.asc >/dev/null
echo "deb https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-17-jdk

# 4) Persist for next WSL sessions
echo "export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))" >> ~/.bashrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc

# 5) Run the app
cd "/mnt/c/Users/jrenu/Workspace/Charles Sche Agentic AI projects"
./mvnw clean spring-boot:run
```

**PowerShell note:** do not run `./mvnw` from PowerShell if Java only works in WSL — stay in the WSL shell (`jrenu@RenuBala:...$`).

API key for mutating endpoints:

```http
X-API-Key: demo-api-key-change-me
```

## After git pull — force a clean UI reload

Your browser + Spring can keep old CSS. After pulling UI changes:

```bash
# 1) confirm you actually have the background markup
grep -n "bg-glow\|build-tag\|20261026b" src/main/resources/templates/index.html

# 2) stop any old Java process on 43127, then rebuild/run
./mvnw clean spring-boot:run
```

Then hard-refresh the browser: **Ctrl+Shift+R** (Windows) / **Cmd+Shift+R** (Mac).

You should see:
- a **blue animated top ribbon**
- **blue/teal glow + grid** behind the cards
- a pill: `UI build 20261026b · atmospheric background enabled`
- Live-run buttons including **Inject FAIL_TESTING** and **Mutate upstream (re-plan)**

If `grep` finds nothing, you pulled a different remote/branch than the one with the UI update.

## What was strengthened (feedback response)

1. **Parallel execution** — ready stages run on a thread pool; `CompletableFuture.allOf` sync barrier
2. **Gates** — `GatePolicy` validates artifacts/context (tests passed, architecture present, release policy)
3. **Retry / fallback / rollback / safe-stop** — retries, `test_fallback` edge, H2 snapshot restore, safe-stop; trigger via `/fault`
4. **Dynamic re-plan** — `/mutate-upstream` changes fingerprint and inserts `REPLAN` stage
5. **Lineage + metrics** — audit events + retries/rollbacks/latency/MTTR from real runs
6. **Policy guardrails** — security/compliance/change-control beyond simple string checks
7. **Shortener reliability** — expiry, delete, custom alias, rate limit, API auth, H2 persistence
8. **Tests** — stricter unit + integration tests including auth and failure-oriented orchestration cases
9. **Docs aligned** — `docs/ARCHITECTURE.md` matches real classes
10. **Final summary** — `docs/FINAL_SUMMARY.md`

## Core APIs

### Orchestration
- `POST /api/orchestration/runs`
- `POST /api/orchestration/runs/{id}/approve|reject|resume|safe-stop`
- `POST /api/orchestration/runs/{id}/fault` `{"type":"FAIL_TESTING","once":false}`
- `POST /api/orchestration/runs/{id}/mutate-upstream` `{"requirement":"..."}`
- `GET /api/orchestration/metrics`

### URL shortener
- `POST /api/urls` `{"url":"https://...","customAlias":"opt","ttlSeconds":3600}`
- `GET /{code}` public redirect
- `DELETE /api/urls/{code}`
- `GET /api/urls/{code}/stats`
- `GET /api/urls/features`

## Scenarios

- **greenfield** — build shortener capabilities under gated SDLC
- **brownfield** — impact analysis + analytics/reliability evolution
- **ambiguous** — start provisional; use mutate-upstream to force dynamic re-plan

## Docs

- `READ.md` — plan overview
- `docs/ARCHITECTURE.md` — components and control flow
- `docs/FINAL_SUMMARY.md` — rationale, evidence, risks, limitations
- `docs/assignment.pdf` — original assignment

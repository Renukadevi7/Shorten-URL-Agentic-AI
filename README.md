# Agentic (Java / Spring Boot)

Local **agent playground** that streams plan → tool → observe → answer. No API key required — the planner is a heuristic demo with mock tools.

## Requirements

- Java 21+
- Maven 3.8+ (or use `./mvnw`)

## Run locally

```bash
./mvnw spring-boot:run
```

Open [http://127.0.0.1:43127](http://127.0.0.1:43127).

Or build a jar:

```bash
./mvnw -DskipTests package
java -jar target/agentic-0.0.1-SNAPSHOT.jar
```

## What’s included

- **Streaming agent API** at `POST /api/agent` (Server-Sent Events)
- **Tools**: `knowledge_search`, `calculator`, `notes`, `weather_mock`
- **UI**: Thymeleaf page + live step timeline

## Project layout

```
src/main/java/com/agentic/
  agent/                 # runtime + step/event models
  agent/tools/           # mock tools
  web/                   # HomeController + AgentController (SSE)
src/main/resources/
  templates/index.html
  static/css/agentic.css
  static/js/agentic.js
```

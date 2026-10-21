# Agentic

A local **agent playground** that streams plan → tool → observe → answer. No API key required — the planner is a heuristic demo with mock tools.

## Run locally

```bash
npm install
npm run dev -- --port 43127
```

Open [http://127.0.0.1:43127](http://127.0.0.1:43127).

## What’s included

- **Streaming agent API** at `POST /api/agent` (Server-Sent Events)
- **Tools**: `knowledge_search`, `calculator`, `notes`, `weather_mock`
- **UI**: live step timeline + final answer panel

## Project layout

```
src/
  app/api/agent/route.ts   # SSE agent endpoint
  components/agent-playground.tsx
  lib/agent/runtime.ts     # plan + tool loop
  lib/agent/tools.ts       # mock tools
```

## Scripts

| Command        | Description              |
| -------------- | ------------------------ |
| `npm run dev`  | Start Next.js dev server |
| `npm run build`| Production build         |
| `npm run start`| Start production server  |
| `npm run lint` | ESLint                   |

import type { ToolResult } from "./types";

export type ToolDefinition = {
  name: string;
  description: string;
  run: (input: string) => Promise<ToolResult>;
};

const KNOWLEDGE: Record<string, string> = {
  agentic:
    "Agentic systems plan a goal, call tools, observe results, and loop until they can answer. Unlike a single chat reply, they keep state across steps.",
  react:
    "The ReAct pattern interleaves Reasoning and Acting: think, pick a tool, observe the result, then decide the next action.",
  tools:
    "Tools are functions the agent may call — search, calculate, fetch data. The model chooses which tool and what arguments.",
  planning:
    "Planning breaks a vague goal into ordered steps so the agent can recover when a tool fails or returns partial data.",
};

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export const tools: ToolDefinition[] = [
  {
    name: "knowledge_search",
    description: "Search a local knowledge base about agents, tools, and planning.",
    async run(query) {
      await sleep(350 + Math.random() * 250);
      const q = query.toLowerCase();
      const hit = Object.entries(KNOWLEDGE).find(([key]) => q.includes(key));
      if (hit) {
        return { ok: true, output: hit[1] };
      }
      if (q.includes("agent") || q.includes("loop") || q.includes("step")) {
        return {
          ok: true,
          output:
            "An agent loop: (1) understand the goal, (2) plan steps, (3) call tools, (4) observe results, (5) synthesize an answer or continue.",
        };
      }
      return {
        ok: true,
        output: `No exact match for "${query}". Related topics: agentic systems, ReAct, tools, planning.`,
      };
    },
  },
  {
    name: "calculator",
    description: "Evaluate a simple arithmetic expression.",
    async run(expression) {
      await sleep(200);
      const cleaned = expression.replace(/[^0-9+\-*/().%\s]/g, "").trim();
      if (!cleaned) {
        return { ok: false, output: "Empty expression." };
      }
      try {
        // Safe enough for demo: only arithmetic characters allowed above.
        const value = Function(`"use strict"; return (${cleaned});`)() as number;
        if (typeof value !== "number" || !Number.isFinite(value)) {
          return { ok: false, output: "Result was not a finite number." };
        }
        return { ok: true, output: String(value) };
      } catch {
        return { ok: false, output: `Could not evaluate: ${expression}` };
      }
    },
  },
  {
    name: "notes",
    description: "Save a short working note the agent can recall later in the run.",
    async run(text) {
      await sleep(150);
      return { ok: true, output: `Noted: ${text.slice(0, 240)}` };
    },
  },
  {
    name: "weather_mock",
    description: "Return mocked weather for a city name.",
    async run(city) {
      await sleep(300);
      const name = city.trim() || "your city";
      const seed = [...name].reduce((a, c) => a + c.charCodeAt(0), 0);
      const temps = [14, 18, 21, 24, 27, 11, 16];
      const conditions = ["clear", "partly cloudy", "overcast", "light rain", "breezy"];
      const temp = temps[seed % temps.length];
      const condition = conditions[seed % conditions.length];
      return {
        ok: true,
        output: `${name}: ${temp}°C, ${condition}. (Mock data for the demo agent.)`,
      };
    },
  },
];

export function getTool(name: string) {
  return tools.find((t) => t.name === name);
}

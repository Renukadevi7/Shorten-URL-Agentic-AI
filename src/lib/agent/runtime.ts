import { getTool, tools } from "./tools";
import type { AgentRunEvent, AgentStep } from "./types";

function id() {
  return `s_${Math.random().toString(36).slice(2, 10)}`;
}

function step(
  kind: AgentStep["kind"],
  title: string,
  detail: string,
  extra?: Partial<AgentStep>,
): AgentStep {
  return {
    id: id(),
    kind,
    title,
    detail,
    status: "running",
    at: Date.now(),
    ...extra,
  };
}

type PlannedAction = {
  toolName: string;
  input: string;
  why: string;
};

function planActions(goal: string): PlannedAction[] {
  const g = goal.toLowerCase();
  const actions: PlannedAction[] = [];

  const mathMatch = goal.match(/(-?\d+(?:\.\d+)?)\s*([+\-*/])\s*(-?\d+(?:\.\d+)?)/);
  if (mathMatch || /calculat|how much|sum|product|divide|multiply|percent|%/.test(g)) {
    const expr =
      mathMatch?.[0] ??
      goal.match(/[\d.+\-*/()\s%]+/)?.[0]?.trim() ??
      "12 * 8";
    actions.push({
      toolName: "calculator",
      input: expr,
      why: "Goal includes a numeric question.",
    });
  }

  if (/weather|temperature|forecast|rain|°|celsius|fahrenheit/.test(g)) {
    const city =
      goal.match(/in ([A-Za-z .'-]+)/i)?.[1]?.replace(/[?.!].*$/, "").trim() ||
      goal.match(/for ([A-Za-z .'-]+)/i)?.[1]?.replace(/[?.!].*$/, "").trim() ||
      "Lisbon";
    actions.push({
      toolName: "weather_mock",
      input: city,
      why: "Goal asks about weather conditions.",
    });
  }

  if (
    /agent|agentic|react|tool|plan|loop|orchestrat|autonomous/.test(g) ||
    actions.length === 0
  ) {
    const topic = /react/.test(g)
      ? "react"
      : /tool/.test(g)
        ? "tools"
        : /plan/.test(g)
          ? "planning"
          : "agentic";
    actions.push({
      toolName: "knowledge_search",
      input: topic,
      why: "Pull grounded notes from the local knowledge base.",
    });
  }

  actions.push({
    toolName: "notes",
    input: `Working goal: ${goal.slice(0, 160)}`,
    why: "Keep a short working memory note before answering.",
  });

  // Deduplicate by tool+input
  const seen = new Set<string>();
  return actions.filter((a) => {
    const key = `${a.toolName}:${a.input}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function synthesize(goal: string, observations: { tool: string; output: string }[]) {
  const lines = [
    `Here’s what the agent did for: “${goal.trim()}”`,
    "",
    "Observations:",
    ...observations.map((o, i) => `${i + 1}. [${o.tool}] ${o.output}`),
    "",
    "Answer:",
  ];

  const calc = observations.find((o) => o.tool === "calculator");
  const weather = observations.find((o) => o.tool === "weather_mock");
  const knowledge = observations.find((o) => o.tool === "knowledge_search");

  if (calc) {
    lines.push(`The calculated result is ${calc.output}.`);
  }
  if (weather) {
    lines.push(weather.output);
  }
  if (knowledge) {
    lines.push(knowledge.output);
  }
  if (!calc && !weather && !knowledge) {
    lines.push(
      "I gathered working notes but need a clearer goal to produce a sharper answer.",
    );
  } else {
    lines.push(
      "This run used a local heuristic planner (no API key). Swap in a model later for open-ended goals.",
    );
  }

  return lines.join("\n");
}

export async function* runAgent(goal: string): AsyncGenerator<AgentRunEvent> {
  const trimmed = goal.trim();
  if (!trimmed) {
    yield { type: "error", message: "Enter a goal for the agent to pursue." };
    return;
  }

  const steps: AgentStep[] = [];
  const emit = async function* (s: AgentStep) {
    steps.push(s);
    yield { type: "step" as const, step: s };
  };

  const planIntro = step(
    "plan",
    "Drafting a plan",
    `Breaking the goal into tool calls. Available tools: ${tools.map((t) => t.name).join(", ")}.`,
  );
  yield* emit(planIntro);

  const actions = planActions(trimmed);
  planIntro.status = "done";
  planIntro.detail = `Planned ${actions.length} action${actions.length === 1 ? "" : "s"}:\n${actions
    .map((a, i) => `${i + 1}. ${a.toolName} — ${a.why}`)
    .join("\n")}`;
  yield { type: "step", step: { ...planIntro } };

  const observations: { tool: string; output: string }[] = [];

  for (const action of actions) {
    const toolStep = step(
      "tool",
      `Calling ${action.toolName}`,
      `Input: ${action.input}`,
      { toolName: action.toolName },
    );
    yield* emit(toolStep);

    const tool = getTool(action.toolName);
    if (!tool) {
      toolStep.status = "error";
      toolStep.detail = `Unknown tool: ${action.toolName}`;
      yield { type: "step", step: { ...toolStep } };
      yield { type: "error", message: `Unknown tool: ${action.toolName}` };
      return;
    }

    const result = await tool.run(action.input);
    toolStep.status = result.ok ? "done" : "error";
    toolStep.detail = result.output;
    yield { type: "step", step: { ...toolStep } };

    const observe = step(
      "observe",
      `Observed ${action.toolName}`,
      result.output,
      { toolName: action.toolName, status: result.ok ? "done" : "error" },
    );
    yield* emit(observe);

    if (!result.ok) {
      yield {
        type: "error",
        message: `Tool ${action.toolName} failed: ${result.output}`,
      };
      return;
    }

    observations.push({ tool: action.toolName, output: result.output });
  }

  const answerText = synthesize(trimmed, observations);
  const answerStep = step("answer", "Final answer", answerText, {
    status: "done",
  });
  yield* emit(answerStep);

  yield { type: "done", answer: answerText, steps: [...steps] };
}

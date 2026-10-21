export type AgentStepKind = "plan" | "tool" | "observe" | "answer" | "error";

export type AgentStep = {
  id: string;
  kind: AgentStepKind;
  title: string;
  detail: string;
  toolName?: string;
  status: "running" | "done" | "error";
  at: number;
};

export type AgentRunRequest = {
  goal: string;
};

export type AgentRunEvent =
  | { type: "step"; step: AgentStep }
  | { type: "done"; answer: string; steps: AgentStep[] }
  | { type: "error"; message: string };

export type ToolResult = {
  ok: boolean;
  output: string;
};

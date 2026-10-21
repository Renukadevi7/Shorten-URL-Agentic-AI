"use client";

import { useEffect, useRef, useState, useTransition } from "react";
import {
  ArrowRight,
  Bot,
  Calculator,
  CloudSun,
  Loader2,
  NotebookPen,
  Search,
  Sparkles,
  StopCircle,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import type { AgentRunEvent, AgentStep } from "@/lib/agent/types";

const EXAMPLES = [
  "Explain how an agentic loop works, then note the key idea.",
  "What is 48 * 12.5, and write a short note about the result?",
  "What's the weather in Lisbon, and how do agents use tools?",
  "Describe the ReAct pattern in plain language.",
];

const TOOL_META: Record<
  string,
  { label: string; icon: typeof Search }
> = {
  knowledge_search: { label: "Knowledge", icon: Search },
  calculator: { label: "Calculator", icon: Calculator },
  notes: { label: "Notes", icon: NotebookPen },
  weather_mock: { label: "Weather", icon: CloudSun },
};

function kindLabel(kind: AgentStep["kind"]) {
  switch (kind) {
    case "plan":
      return "Plan";
    case "tool":
      return "Tool";
    case "observe":
      return "Observe";
    case "answer":
      return "Answer";
    case "error":
      return "Error";
  }
}

export function AgentPlayground() {
  const [goal, setGoal] = useState(EXAMPLES[0]);
  const [steps, setSteps] = useState<AgentStep[]>([]);
  const [answer, setAnswer] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [isPending, startTransition] = useTransition();
  const abortRef = useRef<AbortController | null>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    listRef.current?.scrollTo({
      top: listRef.current.scrollHeight,
      behavior: "smooth",
    });
  }, [steps, answer, error]);

  async function run() {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setRunning(true);
    setError(null);
    setAnswer(null);
    startTransition(() => setSteps([]));

    try {
      const res = await fetch("/api/agent", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ goal }),
        signal: controller.signal,
      });

      if (!res.ok || !res.body) {
        throw new Error(`Request failed (${res.status})`);
      }

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const chunks = buffer.split("\n\n");
        buffer = chunks.pop() ?? "";

        for (const chunk of chunks) {
          const line = chunk
            .split("\n")
            .find((l) => l.startsWith("data: "));
          if (!line) continue;
          const event = JSON.parse(line.slice(6)) as AgentRunEvent;

          if (event.type === "step") {
            setSteps((prev) => {
              const idx = prev.findIndex((s) => s.id === event.step.id);
              if (idx === -1) return [...prev, event.step];
              const next = [...prev];
              next[idx] = event.step;
              return next;
            });
          } else if (event.type === "done") {
            setAnswer(event.answer);
          } else if (event.type === "error") {
            setError(event.message);
          }
        }
      }
    } catch (err) {
      if ((err as Error).name === "AbortError") {
        setError("Run stopped.");
      } else {
        setError(err instanceof Error ? err.message : "Something went wrong.");
      }
    } finally {
      setRunning(false);
    }
  }

  function stop() {
    abortRef.current?.abort();
  }

  const busy = running || isPending;

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-10 px-4 pb-16 pt-8 sm:px-6 lg:px-8">
      <header className="agentic-enter relative flex flex-col gap-5">
        <p className="font-display text-5xl tracking-tight text-[var(--ink)] sm:text-6xl md:text-7xl">
          Agentic
        </p>
        <p className="max-w-xl text-lg leading-relaxed text-[var(--ink-muted)] sm:text-xl">
          Watch a local agent plan, call tools, observe results, and answer —
          no API key required.
        </p>
        <div className="flex flex-wrap gap-2">
          <Button
            size="lg"
            className="bg-[var(--accent)] text-[var(--accent-fg)] hover:bg-[var(--accent-hover)]"
            onClick={run}
            disabled={busy || !goal.trim()}
          >
            {busy ? (
              <>
                <Loader2 className="animate-spin" />
                Running
              </>
            ) : (
              <>
                Run agent
                <ArrowRight />
              </>
            )}
          </Button>
          {busy ? (
            <Button size="lg" variant="outline" onClick={stop}>
              <StopCircle />
              Stop
            </Button>
          ) : null}
        </div>
      </header>

      <section className="grid gap-8 lg:grid-cols-[minmax(0,1.05fr)_minmax(0,0.95fr)]">
        <div className="flex flex-col gap-4">
          <label className="text-sm font-medium text-[var(--ink-muted)]" htmlFor="goal">
            Goal
          </label>
          <Textarea
            id="goal"
            value={goal}
            onChange={(e) => setGoal(e.target.value)}
            rows={5}
            className="min-h-32 resize-y border-[var(--line)] bg-white/70 text-base shadow-none backdrop-blur-sm"
            placeholder="Ask the agent to research, calculate, or check the weather…"
            disabled={busy}
          />
          <div className="flex flex-wrap gap-2">
            {EXAMPLES.map((example) => (
              <button
                key={example}
                type="button"
                disabled={busy}
                onClick={() => setGoal(example)}
                className={cn(
                  "rounded-md border border-[var(--line)] bg-white/50 px-3 py-1.5 text-left text-xs text-[var(--ink-muted)] transition hover:border-[var(--accent)] hover:text-[var(--ink)]",
                  goal === example && "border-[var(--accent)] text-[var(--ink)]",
                )}
              >
                {example}
              </button>
            ))}
          </div>

          <div className="mt-2 grid gap-3 sm:grid-cols-2">
            {Object.entries(TOOL_META).map(([name, meta]) => {
              const Icon = meta.icon;
              return (
                <div
                  key={name}
                  className="flex items-start gap-3 rounded-lg border border-[var(--line)] bg-white/40 px-3 py-3"
                >
                  <Icon className="mt-0.5 size-4 text-[var(--accent)]" />
                  <div>
                    <p className="text-sm font-medium text-[var(--ink)]">{meta.label}</p>
                    <p className="font-mono text-[11px] text-[var(--ink-muted)]">{name}</p>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        <div className="flex min-h-[28rem] flex-col overflow-hidden rounded-xl border border-[var(--line)] bg-white/55 shadow-[0_20px_60px_-40px_rgba(20,40,30,0.45)] backdrop-blur-md">
          <div className="flex items-center justify-between border-b border-[var(--line)] px-4 py-3">
            <div className="flex items-center gap-2 text-sm font-medium text-[var(--ink)]">
              <Bot className="size-4 text-[var(--accent)]" />
              Live run
            </div>
            <Badge variant="secondary" className="font-mono text-[10px] uppercase tracking-wide">
              {busy ? "streaming" : steps.length ? "idle" : "ready"}
            </Badge>
          </div>

          <div ref={listRef} className="flex-1 space-y-3 overflow-y-auto px-4 py-4">
            {!steps.length && !error && !answer ? (
              <div className="flex h-full min-h-64 flex-col items-center justify-center gap-3 text-center text-[var(--ink-muted)]">
                <Sparkles className="size-6 text-[var(--accent)] opacity-80" />
                <p className="max-w-xs text-sm leading-relaxed">
                  Press <span className="font-medium text-[var(--ink)]">Run agent</span> to
                  stream plan → tool → observe → answer.
                </p>
              </div>
            ) : null}

            {steps.map((s, index) => (
              <article
                key={s.id}
                className="agentic-step rounded-lg border border-[var(--line)] bg-[var(--panel)] p-3"
                style={{ animationDelay: `${Math.min(index, 8) * 40}ms` }}
              >
                <div className="mb-2 flex flex-wrap items-center gap-2">
                  <Badge
                    variant="outline"
                    className={cn(
                      "font-mono text-[10px] uppercase",
                      s.kind === "answer" && "border-[var(--accent)] text-[var(--accent)]",
                      s.status === "running" && "animate-pulse",
                      s.status === "error" && "border-red-500 text-red-600",
                    )}
                  >
                    {kindLabel(s.kind)}
                  </Badge>
                  {s.toolName ? (
                    <span className="font-mono text-[11px] text-[var(--ink-muted)]">
                      {s.toolName}
                    </span>
                  ) : null}
                  <span className="ml-auto text-[11px] text-[var(--ink-muted)]">{s.title}</span>
                </div>
                <pre className="whitespace-pre-wrap font-sans text-sm leading-relaxed text-[var(--ink)]">
                  {s.detail}
                </pre>
              </article>
            ))}

            {error ? (
              <div
                role="alert"
                className="rounded-lg border border-red-200 bg-red-50 px-3 py-3 text-sm text-red-800"
              >
                {error}
              </div>
            ) : null}
          </div>

          {answer ? (
            <div className="border-t border-[var(--line)] bg-[var(--panel-strong)] px-4 py-4">
              <p className="mb-2 text-xs font-medium uppercase tracking-wide text-[var(--ink-muted)]">
                Final answer
              </p>
              <pre className="whitespace-pre-wrap font-sans text-sm leading-relaxed text-[var(--ink)]">
                {answer}
              </pre>
            </div>
          ) : null}
        </div>
      </section>
    </div>
  );
}

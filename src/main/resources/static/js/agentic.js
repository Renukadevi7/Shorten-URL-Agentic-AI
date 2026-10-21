const EXAMPLES = [
  "Explain how an agentic loop works, then note the key idea.",
  "What is 48 * 12.5, and write a short note about the result?",
  "What's the weather in Lisbon, and how do agents use tools?",
  "Describe the ReAct pattern in plain language.",
];

const goalEl = document.getElementById("goal");
const runBtn = document.getElementById("run");
const stopBtn = document.getElementById("stop");
const stepsEl = document.getElementById("steps");
const badgeEl = document.getElementById("badge");
const answerEl = document.getElementById("answer");
const answerBody = document.getElementById("answer-body");
const examplesEl = document.getElementById("examples");

let abortController = null;
const stepMap = new Map();

function setBusy(busy) {
  runBtn.disabled = busy || !goalEl.value.trim();
  stopBtn.hidden = !busy;
  badgeEl.textContent = busy ? "streaming" : stepMap.size ? "idle" : "ready";
}

function kindLabel(kind) {
  return kind || "step";
}

function renderEmpty() {
  stepsEl.innerHTML = `
    <div class="empty">
      Press <strong>Run agent</strong> to stream plan → tool → observe → answer.
    </div>`;
}

function renderSteps() {
  if (!stepMap.size) {
    renderEmpty();
    return;
  }
  const parts = [];
  for (const step of stepMap.values()) {
    parts.push(`
      <article class="step">
        <div class="step-meta">
          <span class="kind ${step.kind || ""} ${step.status || ""}">${kindLabel(step.kind)}</span>
          ${step.toolName ? `<span class="tool-name">${escapeHtml(step.toolName)}</span>` : ""}
          <span class="step-title">${escapeHtml(step.title || "")}</span>
        </div>
        <pre>${escapeHtml(step.detail || "")}</pre>
      </article>`);
  }
  stepsEl.innerHTML = parts.join("");
  stepsEl.scrollTop = stepsEl.scrollHeight;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function upsertStep(step) {
  stepMap.set(step.id, step);
  renderSteps();
}

function showError(message) {
  stepsEl.insertAdjacentHTML(
    "beforeend",
    `<div class="alert" role="alert">${escapeHtml(message)}</div>`,
  );
  stepsEl.scrollTop = stepsEl.scrollHeight;
}

async function runAgent() {
  abortController?.abort();
  abortController = new AbortController();
  stepMap.clear();
  answerEl.hidden = true;
  answerBody.textContent = "";
  renderEmpty();
  setBusy(true);

  try {
    const res = await fetch("/api/agent", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({ goal: goalEl.value }),
      signal: abortController.signal,
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
        const line = chunk.split("\n").find((l) => l.startsWith("data:"));
        if (!line) continue;
        const payload = line.replace(/^data:\s?/, "");
        let event;
        try {
          event = JSON.parse(payload);
        } catch {
          continue;
        }

        if (event.type === "step" && event.step) {
          upsertStep(event.step);
        } else if (event.type === "done") {
          answerBody.textContent = event.answer || "";
          answerEl.hidden = false;
        } else if (event.type === "error") {
          showError(event.message || "Agent run failed.");
        }
      }
    }
  } catch (err) {
    if (err.name === "AbortError") {
      showError("Run stopped.");
    } else {
      showError(err.message || "Something went wrong.");
    }
  } finally {
    setBusy(false);
  }
}

EXAMPLES.forEach((example) => {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "chip" + (example === EXAMPLES[0] ? " active" : "");
  btn.textContent = example;
  btn.addEventListener("click", () => {
    goalEl.value = example;
    [...examplesEl.children].forEach((c) => c.classList.remove("active"));
    btn.classList.add("active");
    setBusy(false);
  });
  examplesEl.appendChild(btn);
});

goalEl.value = EXAMPLES[0];
goalEl.addEventListener("input", () => setBusy(false));
runBtn.addEventListener("click", runAgent);
stopBtn.addEventListener("click", () => abortController?.abort());
renderEmpty();
setBusy(false);

const API_KEY = "demo-api-key-change-me";
const scenarioEl = document.getElementById("scenario");
const requirementEl = document.getElementById("requirement");
const runView = document.getElementById("run-view");
const metricsView = document.getElementById("metrics");
const statusEl = document.getElementById("status");

const PRESETS = {
  greenfield: "Build a URL shortener with create and redirect APIs over HTTPS.",
  brownfield: "Add click analytics, delete/expiry/alias and reliability to the existing shortener.",
  ambiguous: "Make links better for the leadership demo next week.",
};

let currentRunId = null;

function headers(json = true) {
  const h = { "X-API-Key": API_KEY };
  if (json) h["Content-Type"] = "application/json";
  return h;
}

document.querySelectorAll(".chip").forEach((chip) => {
  chip.addEventListener("click", () => {
    document.querySelectorAll(".chip").forEach((c) => c.classList.remove("active"));
    chip.classList.add("active");
    scenarioEl.value = chip.dataset.scenario;
    requirementEl.value = PRESETS[chip.dataset.scenario];
  });
});

scenarioEl.addEventListener("change", () => {
  requirementEl.value = PRESETS[scenarioEl.value] || requirementEl.value;
});

async function refreshMetrics() {
  const res = await fetch("/api/orchestration/metrics");
  const data = await res.json();
  const m = data.reliability || {};
  metricsView.innerHTML = [
    ["Success %", m.successRatePercent ?? 0],
    ["Retries", m.retryCount ?? 0],
    ["Rollbacks", m.rollbackCount ?? 0],
    ["Avg latency ms", m.avgEndToEndLatencyMs ?? 0],
    ["Avg MTTR ms", m.avgMttrMs ?? 0],
  ]
    .map(([k, v]) => `<div class="metric"><b>${v}</b><span>${k}</span></div>`)
    .join("");
}

function renderRun(run) {
  currentRunId = run.id;
  statusEl.textContent = run.status;
  const stages = (run.stages || [])
    .map(
      (s) => `
      <div class="stage">
        <div class="stage-head">
          <strong>${escapeHtml(s.title)}</strong>
          <span class="badge ${s.status}">${s.status}</span>
        </div>
        <div class="small">${escapeHtml(s.type)} · deps: ${(s.dependsOn || []).join(", ") || "none"}
          · fallback: ${s.fallbackStageId || "—"} · approval: ${s.requiresHumanApproval}</div>
        ${s.output ? `<pre>${escapeHtml(s.output)}</pre>` : ""}
      </div>`
    )
    .join("");

  const lineage = (run.lineage || [])
    .slice(-14)
    .reverse()
    .map((e) => `<div class="small">[${escapeHtml(e.kind)}] ${escapeHtml(e.message)}</div>`)
    .join("");

  runView.innerHTML = `
    <div class="small">Run <code>${escapeHtml(run.id)}</code> · ${escapeHtml(run.scenario)}
      · retries ${run.retries} · rollbacks ${run.rollbacks} · latency ${run.latencyMs}ms</div>
    <p class="small"><strong>Normalized:</strong> ${escapeHtml(run.normalizedProblem || "—")}</p>
    <div class="row">
      <button id="approve" ${run.status === "AWAITING_APPROVAL" ? "" : "disabled"}>Approve gate</button>
      <button id="reject" class="danger" ${run.status === "AWAITING_APPROVAL" ? "" : "disabled"}>Reject</button>
      <button id="resume" class="secondary" ${run.status === "SAFE_STOPPED" ? "" : "disabled"}>Resume</button>
      <button id="stop" class="warn">Safe stop</button>
      <button id="fault" class="warn">Inject FAIL_TESTING</button>
      <button id="replan" class="secondary">Mutate upstream (re-plan)</button>
    </div>
    <h2 style="margin-top:1rem">Stages (DAG)</h2>
    ${stages}
    <h2>Decision lineage</h2>
    <div class="lineage card">${lineage || "<div class='small'>No events yet</div>"}</div>
  `;

  bind("approve", async () =>
    post(`/api/orchestration/runs/${currentRunId}/approve`, { actor: "operator" }));
  bind("reject", async () =>
    post(`/api/orchestration/runs/${currentRunId}/reject`, { reason: "Operator rejected" }));
  bind("resume", async () => post(`/api/orchestration/runs/${currentRunId}/resume`));
  bind("stop", async () =>
    post(`/api/orchestration/runs/${currentRunId}/safe-stop`, { reason: "Operator safe-stop" }));
  bind("fault", async () =>
    post(`/api/orchestration/runs/${currentRunId}/fault`, { type: "FAIL_TESTING", once: false }));
  bind("replan", async () =>
    post(`/api/orchestration/runs/${currentRunId}/mutate-upstream`, {
      requirement: "Updated upstream: ship HTTPS-only short links with stats for leadership demo.",
    }));
}

function bind(id, fn) {
  document.getElementById(id)?.addEventListener("click", async () => {
    const data = await fn();
    if (data) {
      renderRun(data);
      refreshMetrics();
    }
  });
}

async function post(url, body) {
  const res = await fetch(url, {
    method: "POST",
    headers: headers(body !== undefined),
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    runView.insertAdjacentHTML(
      "afterbegin",
      `<div class="small" style="color:#b91c1c">${escapeHtml(data.message || data.error || res.statusText)}</div>`
    );
    return null;
  }
  return data;
}

function escapeHtml(v) {
  return String(v ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

document.getElementById("start").addEventListener("click", async () => {
  const data = await post("/api/orchestration/runs", {
    scenario: scenarioEl.value,
    requirement: requirementEl.value,
  });
  if (data) {
    renderRun(data);
    refreshMetrics();
  }
});

requirementEl.value = PRESETS.greenfield;
refreshMetrics();

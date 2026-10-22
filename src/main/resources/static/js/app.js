const scenarioEl = document.getElementById("scenario");
const requirementEl = document.getElementById("requirement");
const runView = document.getElementById("run-view");
const metricsView = document.getElementById("metrics");
const statusEl = document.getElementById("status");

const PRESETS = {
  greenfield: "Build a URL shortener with create and redirect APIs.",
  brownfield: "Add click analytics and basic reliability to the existing shortener.",
  ambiguous: "Make links better for the leadership demo next week.",
};

let currentRunId = null;

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
        <div class="small">${escapeHtml(s.type)} · deps: ${(s.dependsOn || []).join(", ") || "none"} · approvals: ${s.requiresHumanApproval}</div>
        ${s.output ? `<pre>${escapeHtml(s.output)}</pre>` : ""}
      </div>`
    )
    .join("");

  const lineage = (run.lineage || [])
    .slice(-12)
    .reverse()
    .map((e) => `<div class="small">[${escapeHtml(e.kind)}] ${escapeHtml(e.message)}</div>`)
    .join("");

  runView.innerHTML = `
    <div class="small">Run <code>${escapeHtml(run.id)}</code> · scenario ${escapeHtml(run.scenario)} · retries ${run.retries} · rollbacks ${run.rollbacks}</div>
    <p class="small"><strong>Normalized:</strong> ${escapeHtml(run.normalizedProblem || "—")}</p>
    <div class="row">
      <button id="approve" ${run.status === "AWAITING_APPROVAL" ? "" : "disabled"}>Approve gate</button>
      <button id="reject" class="danger" ${run.status === "AWAITING_APPROVAL" ? "" : "disabled"}>Reject</button>
      <button id="resume" class="secondary" ${run.status === "SAFE_STOPPED" ? "" : "disabled"}>Resume</button>
      <button id="stop" class="warn">Safe stop</button>
    </div>
    <h2 style="margin-top:1rem">Stages (DAG)</h2>
    ${stages}
    <h2>Decision lineage</h2>
    <div class="lineage card">${lineage || "<div class='small'>No events yet</div>"}</div>
  `;

  document.getElementById("approve")?.addEventListener("click", async () => {
    const res = await fetch(`/api/orchestration/runs/${currentRunId}/approve`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actor: "operator" }),
    });
    renderRun(await res.json());
    refreshMetrics();
  });
  document.getElementById("reject")?.addEventListener("click", async () => {
    const res = await fetch(`/api/orchestration/runs/${currentRunId}/reject`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reason: "Operator rejected high-impact action" }),
    });
    renderRun(await res.json());
    refreshMetrics();
  });
  document.getElementById("resume")?.addEventListener("click", async () => {
    const res = await fetch(`/api/orchestration/runs/${currentRunId}/resume`, { method: "POST" });
    renderRun(await res.json());
    refreshMetrics();
  });
  document.getElementById("stop")?.addEventListener("click", async () => {
    const res = await fetch(`/api/orchestration/runs/${currentRunId}/safe-stop`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reason: "Operator safe-stop" }),
    });
    renderRun(await res.json());
    refreshMetrics();
  });
}

function escapeHtml(v) {
  return String(v ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

document.getElementById("start").addEventListener("click", async () => {
  const res = await fetch("/api/orchestration/runs", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      scenario: scenarioEl.value,
      requirement: requirementEl.value,
    }),
  });
  const data = await res.json();
  if (!res.ok) {
    runView.innerHTML = `<div class="small" style="color:#b91c1c">${escapeHtml(data.message || data.detail || "Failed to start")}</div>`;
    return;
  }
  renderRun(data);
  refreshMetrics();
});

requirementEl.value = PRESETS.greenfield;
refreshMetrics();

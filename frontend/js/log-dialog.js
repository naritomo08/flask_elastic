import { escapeHtml } from "./utils.js";

export function showLogDetail(log, dialog, title, body) {
  if (!log) return;
  title.textContent = `${log.host || "unknown host"} / ${log.program || "unknown program"}`;
  const preferred = ["display_time", "log_type", "host", "program", "msg", "severity", "index", "id"];
  const keys = [
    ...preferred.filter((key) => key in log),
    ...Object.keys(log).filter((key) => !preferred.includes(key) && key !== "score")
  ];
  body.innerHTML = `
    <dl class="log-fields">${keys.map((key) => `<div><dt>${escapeHtml(key)}</dt><dd>${renderValue(log[key])}</dd></div>`).join("")}</dl>
    <div class="raw-json"><div><strong>Raw JSON</strong><button type="button" data-copy-json>コピー</button></div><pre>${escapeHtml(JSON.stringify(log, null, 2))}</pre></div>`;
  body.querySelector("[data-copy-json]").addEventListener("click", async (event) => {
    await navigator.clipboard.writeText(JSON.stringify(log, null, 2));
    event.currentTarget.textContent = "コピーしました";
  });
  dialog.showModal();
}

function renderValue(value) {
  if (value == null || value === "") return "<span class=\"muted\">—</span>";
  return `<span>${escapeHtml(typeof value === "object" ? JSON.stringify(value) : value)}</span>`;
}

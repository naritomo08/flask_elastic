import { BACKENDS } from "./config.js";
import { healthCard } from "./views.js";

let healthTimer;

export async function renderHealth(app, refreshBackendAvailability) {
  document.title = "稼働状況 | Elastic Log Explorer";
  app.innerHTML = `
    <section class="health-page">
      <div class="health-page-header"><div><p class="eyebrow">SYSTEM HEALTH</p><h1>稼働状況</h1><p>6つのバックエンドとElasticsearchへの接続状態を確認します。</p></div><button class="button-secondary" type="button" data-health-refresh>↻ 今すぐ更新</button></div>
      <div class="health-summary"><span class="health-dot is-checking"></span><strong data-health-summary>確認中…</strong><time data-health-updated></time></div>
      <div class="health-grid">${Object.entries(BACKENDS).map(([id, backend]) => healthCard(id, backend)).join("")}</div>
    </section>`;
  await updateHealth(refreshBackendAvailability);
  healthTimer = window.setInterval(() => updateHealth(refreshBackendAvailability), 5000);
}

export async function updateHealth(refreshBackendAvailability) {
  if (location.pathname !== "/health") return;
  const results = await refreshBackendAvailability();
  results.forEach(updateHealthCard);
  const okCount = results.filter((result) => result.ok).length;
  const summary = document.querySelector("[data-health-summary]");
  const dot = document.querySelector(".health-dot");
  const updated = document.querySelector("[data-health-updated]");
  if (summary) summary.textContent = okCount === results.length ? "すべてのサービスが正常です" : `${okCount} / ${results.length} バックエンド正常`;
  dot?.classList.remove("is-checking", "is-healthy", "is-unhealthy");
  dot?.classList.add(okCount === results.length ? "is-healthy" : "is-unhealthy");
  if (updated) updated.textContent = `最終更新 ${new Date().toLocaleTimeString("ja-JP")}`;
}

export function stopHealth() {
  window.clearInterval(healthTimer);
  healthTimer = undefined;
}

function updateHealthCard(result) {
  const card = document.querySelector(`[data-health-card="${result.id}"]`);
  if (!card) return;
  card.classList.remove("is-checking", "is-healthy", "is-unhealthy");
  card.classList.add(result.ok ? "is-healthy" : "is-unhealthy");
  card.querySelector(".health-badge").textContent = result.ok ? "稼働中" : "接続不可";
  card.querySelector(".health-message").textContent = result.ok ? "バックエンドからElasticsearchへ接続できています。" : result.error || result.payload?.error || "接続できませんでした。";
  card.querySelector("[data-backend-state]").textContent = result.error ? "停止" : "応答あり";
  card.querySelector("[data-es-state]").textContent = result.payload?.ok ? "正常" : "接続不可";
  card.querySelector("[data-latency]").textContent = `${result.payload?.latency_ms ?? result.latency} ms`;
  card.querySelector("[data-version]").textContent = result.payload?.version || "—";
  card.querySelector("[data-index]").textContent = result.payload?.index || "—";
}

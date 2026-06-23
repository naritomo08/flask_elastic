import { BACKENDS } from "./config.js";
import { escapeHtml } from "./utils.js";
import { healthCard } from "./views.js";

let healthTimer;
let accessLogs = [];
let accessLogDate = "";

export async function renderHealth(app, refreshBackendAvailability) {
  document.title = "稼働状況 | Elastic Log Explorer";
  app.innerHTML = `
    <section class="health-page">
      <div class="health-page-header"><div><p class="eyebrow">SYSTEM HEALTH</p><h1>稼働状況</h1><p>6つのバックエンドとElasticsearchへの接続状態を確認します。</p></div><button class="button-secondary" type="button" data-health-refresh>↻ 今すぐ更新</button></div>
      <div class="health-summary"><span class="health-dot is-checking"></span><strong data-health-summary>確認中…</strong><time data-health-updated></time></div>
      <div class="health-grid">${Object.entries(BACKENDS).map(([id, backend]) => healthCard(id, backend)).join("")}</div>
      <section class="access-log-section">
        <div class="access-log-heading">
          <div><p class="eyebrow">ACCESS LOGS</p><h2>アクセスログ</h2><p>監視リクエストと静的ファイルを除いた、実際の利用者操作を表示します。</p></div>
          <form class="access-log-controls" data-access-log-form>
            <label>対象日 <input type="date" name="date" value="${todayJst()}"></label>
            <button class="button-secondary" type="submit">表示</button>
            <button class="button-secondary" type="button" data-access-logs-csv>CSVダウンロード</button>
          </form>
        </div>
        <div class="access-log-summary" data-access-log-count>読込中…</div>
        <div class="access-log-table-wrap">
          <table class="access-log-table">
            <thead><tr><th>時刻</th><th>接続元</th><th>Method</th><th>URI</th><th>Status</th><th>応答時間</th></tr></thead>
            <tbody data-access-log-body><tr><td colspan="6">アクセスログを読み込んでいます…</td></tr></tbody>
          </table>
        </div>
      </section>
    </section>`;
  accessLogDate = todayJst();
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
  await updateAccessLogs({ date: accessLogDate });
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

export async function updateAccessLogs({ date = accessLogDate } = {}) {
  const body = document.querySelector("[data-access-log-body]");
  if (!body) return;
  accessLogDate = String(date || todayJst());
  const dateInput = document.querySelector("[data-access-log-form] input[name=date]");
  if (dateInput) dateInput.value = accessLogDate;
  try {
    const params = new URLSearchParams({ date: accessLogDate, tail: "100" });
    const response = await fetch(`/api/access-logs?${params}`, { cache: "no-store" });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || "取得できませんでした");
    accessLogs = payload.logs || [];
    document.querySelector("[data-access-log-count]").textContent = `${payload.date} / ${payload.count}件（直近100件まで）`;
    body.innerHTML = accessLogs.length
      ? [...accessLogs].reverse().map(accessLogRow).join("")
      : `<tr><td colspan="6">対象日のアクセスログはありません。</td></tr>`;
  } catch (error) {
    body.innerHTML = `<tr><td colspan="6">${escapeHtml(error.message)}</td></tr>`;
    document.querySelector("[data-access-log-count]").textContent = "取得失敗";
  }
}

function accessLogRow(log) {
  const timestamp = log["@timestamp"] ? new Date(log["@timestamp"]).toLocaleString("ja-JP") : "—";
  const status = Number(log.status);
  return `<tr>
    <td>${escapeHtml(timestamp)}</td>
    <td>${escapeHtml(log.remote_addr || "—")}</td>
    <td>${escapeHtml(log.method || "—")}</td>
    <td class="access-log-uri" title="${escapeHtml(log.uri || "")}">${escapeHtml(log.uri || "—")}</td>
    <td class="${status >= 400 ? "is-error" : ""}">${escapeHtml(log.status ?? "—")}</td>
    <td>${escapeHtml(log.request_time ?? "—")} s</td>
  </tr>`;
}

export async function downloadAccessLogsCsv() {
  const params = new URLSearchParams({ date: accessLogDate || todayJst(), full: "1" });
  const response = await fetch(`/api/access-logs?${params}`, { cache: "no-store" });
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || "アクセスログを取得できませんでした。");
  const fields = ["@timestamp", "remote_addr", "method", "uri", "status", "body_bytes_sent", "request_time", "upstream_addr", "user_agent"];
  const rows = [fields, ...(payload.logs || []).map((log) => fields.map((field) => log[field] ?? ""))];
  const csv = rows.map((row) => row.map((value) => `"${String(value).replaceAll("\"", "\"\"")}"`).join(",")).join("\r\n");
  const url = URL.createObjectURL(new Blob([`\uFEFF${csv}\r\n`], { type: "text/csv;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = `access-logs-${payload.date}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}

function todayJst() {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Tokyo",
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(new Date());
  const values = Object.fromEntries(parts.map(({ type, value }) => [type, value]));
  return `${values.year}-${values.month}-${values.day}`;
}

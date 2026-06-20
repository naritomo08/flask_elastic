const BACKENDS = {
  flask: { label: "Python / Flask", color: "#3776ab" },
  elixir: { label: "Elixir", color: "#6e4a7e" },
  php: { label: "PHP", color: "#777bb4" },
  java: { label: "Java", color: "#b07219" },
  go: { label: "Go", color: "#00add8" },
  ruby: { label: "Ruby", color: "#cc342d" }
};

const app = document.querySelector("#app");
const backendSelect = document.querySelector("#backend-select");
const apiLink = document.querySelector("#api-link");
const logDialog = document.querySelector("#log-dialog");
const dialogBody = document.querySelector("#dialog-body");
const dialogTitle = document.querySelector("#dialog-title");
let selectedBackend = localStorage.getItem("elastic-log-backend") || "flask";
let currentResults = [];
let healthTimer;

if (!BACKENDS[selectedBackend]) selectedBackend = "flask";
backendSelect.value = selectedBackend;

window.addEventListener("popstate", renderRoute);
backendSelect.addEventListener("change", async () => {
  selectedBackend = backendSelect.value;
  localStorage.setItem("elastic-log-backend", selectedBackend);
  app.innerHTML = `<div class="loading-state">${escapeHtml(BACKENDS[selectedBackend].label)}へ切り替え中…</div>`;
  await renderRoute();
});

document.addEventListener("click", async (event) => {
  const routeLink = event.target.closest("a[data-route]");
  if (routeLink && routeLink.origin === location.origin) {
    event.preventDefault();
    history.pushState({}, "", routeLink.href);
    await renderRoute();
    return;
  }

  const detailButton = event.target.closest("[data-log-index]");
  if (detailButton) {
    showLogDetail(Number(detailButton.dataset.resultIndex));
    return;
  }

  if (event.target.closest("[data-dialog-close]")) logDialog.close();
  if (event.target.closest("[data-health-refresh]")) await updateHealth();
  if (event.target.closest("[data-download-csv]")) downloadCsv(currentResults);
});

document.addEventListener("submit", (event) => {
  if (!event.target.matches("[data-search-form]")) return;
  event.preventDefault();
  const params = new URLSearchParams(new FormData(event.target));
  [...params.entries()].forEach(([key, value]) => {
    if (!String(value).trim()) params.delete(key);
  });
  params.set("page", "1");
  params.set("size", params.get("size") || "20");
  history.pushState({}, "", `/search?${params}`);
  renderRoute();
});

document.addEventListener("reset", (event) => {
  if (!event.target.matches("[data-search-form]")) return;
  window.setTimeout(() => {
    history.pushState({}, "", "/search");
    renderRoute();
  });
});

renderRoute();

async function renderRoute() {
  stopHealth();
  window.scrollTo({ top: 0 });
  try {
    if (location.pathname === "/health") {
      await renderHealth();
    } else if (location.pathname === "/search") {
      await renderSearch();
    } else {
      await renderHome();
    }
  } catch (error) {
    renderError(error.message || "画面を表示できませんでした。");
  }
}

async function renderHome() {
  document.title = "Elastic Log Explorer";
  const data = await api("/logs", { page: 1, size: 6 });
  currentResults = data.results || data.logs || [];
  const total = Number(data.total ?? currentResults.length);
  apiLink.href = apiPath("/logs?page=1&size=6");
  app.innerHTML = `
    <section class="hero">
      <p class="eyebrow">OPERATIONAL LOG DISCOVERY</p>
      <h1>必要なログへ、<br>すばやく辿り着く。</h1>
      <p class="hero-copy">Elasticsearchに蓄積されたsyslog・authlogを、時刻、ホスト、プログラム、メッセージから横断検索できます。</p>
      <div class="log-total" aria-label="現在のログ総量">
        <span>現在のログ総量</span>
        <strong>${total.toLocaleString("ja-JP")}<small> 件</small></strong>
      </div>
      ${searchForm({}, true)}
    </section>
    <section class="section">
      <div class="section-heading">
        <div><p class="eyebrow">RECENT EVENTS</p><h2>最近のログ</h2></div>
        <a class="text-link" href="/search" data-route>すべて表示 →</a>
      </div>
      ${currentResults.length ? `<div class="log-grid">${currentResults.map((log, index) => logCard(log, index)).join("")}</div>` : emptyState("ログがありません", "Elasticsearchの接続先とインデックスを確認してください。")}
    </section>`;
}

async function renderSearch() {
  const params = new URLSearchParams(location.search);
  const page = positiveInt(params.get("page"), 1);
  const size = Math.min(positiveInt(params.get("size"), 20), 100);
  params.set("page", page);
  params.set("size", size);
  document.title = "ログ検索 | Elastic Log Explorer";
  app.innerHTML = `<div class="loading-state">ログを検索しています…</div>`;
  const data = await api("/logs", Object.fromEntries(params));
  currentResults = data.results || data.logs || [];
  const total = Number(data.total ?? currentResults.length);
  const totalPages = Math.max(1, Math.ceil(total / size));
  apiLink.href = apiPath(`/logs?${params}`);

  app.innerHTML = `
    <section class="search-page-header">
      <p class="eyebrow">LOG SEARCH</p>
      <div class="results-summary"><div><h1>ログ検索</h1><p>条件はURLに保存されるため、そのまま共有できます。</p></div><strong>${total.toLocaleString()}<small> 件</small></strong></div>
      ${searchForm(Object.fromEntries(params))}
    </section>
    <section class="section search-results">
      <div class="result-toolbar">
        <span>${page} / ${totalPages} ページ</span>
        ${currentResults.length ? `<button type="button" class="button-secondary" data-download-csv>CSVダウンロード</button>` : ""}
      </div>
      ${currentResults.length ? `<div class="result-list">${currentResults.map((log, index) => resultCard(log, index, params.get("message") || "", params)).join("")}</div>${pagination(params, page, totalPages)}` : emptyState("一致するログがありません", "条件を減らすか、検索期間を広げてみてください。")}
    </section>`;
}

function searchForm(values = {}, hero = false) {
  return `
    <form class="search-form ${hero ? "hero-search" : "advanced-search"}" data-search-form>
      <div class="primary-search">
        <span class="search-icon" aria-hidden="true"></span>
        <input name="message" type="search" value="${escapeHtml(values.message || "")}" placeholder="メッセージを検索（例: timeout, accepted）">
        <button type="submit">検索</button>
      </div>
      <details class="filters" ${hero ? "" : "open"}>
        <summary>詳細条件</summary>
        <div class="filter-grid">
          <label><span>From (JST)</span><input type="datetime-local" name="time_from" value="${escapeHtml(values.time_from || "")}"></label>
          <label><span>To (JST)</span><input type="datetime-local" name="time_to" value="${escapeHtml(values.time_to || "")}"></label>
          <label><span>Log</span><select name="log_type"><option value="">すべて</option>${option("syslog", values.log_type)}${option("authlog", values.log_type)}</select></label>
          <label><span>Host</span><input name="host" value="${escapeHtml(values.host || "")}" placeholder="elastic1"></label>
          <label><span>Program</span><input name="program" value="${escapeHtml(values.program || "")}" placeholder="sshd"></label>
          <label><span>表示件数</span><select name="size">${[10, 20, 50, 100].map((value) => option(String(value), String(values.size || 20), `${value}件`)).join("")}</select></label>
        </div>
        <div class="filter-actions"><button type="reset" class="button-ghost">条件をクリア</button><button type="submit">この条件で検索</button></div>
      </details>
    </form>`;
}

function option(value, selected, label = value) {
  return `<option value="${value}" ${String(selected || "") === value ? "selected" : ""}>${label}</option>`;
}

function logCard(log, index) {
  return `
    <article class="log-card">
      <div class="card-meta"><time>${escapeHtml(log.display_time || "時刻不明")}</time>${filterBadge(log.log_type)}</div>
      <h3>${searchFilterLink("program", log.program, "unknown program")}</h3>
      <p class="host-label">${searchFilterLink("host", log.host, "unknown host")}</p>
      <p class="message-preview">${escapeHtml(log.msg || "メッセージなし")}</p>
      <button type="button" class="card-link" data-log-index="${index}" data-result-index="${index}">ログ詳細を見る <span>→</span></button>
    </article>`;
}

function resultCard(log, index, keyword, params) {
  return `
    <article class="result-card">
      <div class="result-card-top">
        <div class="card-meta"><time>${escapeHtml(log.display_time || "時刻不明")}</time>${filterBadge(log.log_type, params)}${log.severity ? `<span>${escapeHtml(log.severity)}</span>` : ""}</div>
        <span class="index-name">${escapeHtml(log.index || "")}</span>
      </div>
      <div class="result-identity">
        ${searchFilterLink("host", log.host, "unknown host", params)}
        <span>/</span>
        ${searchFilterLink("program", log.program, "unknown program", params)}
      </div>
      <p class="result-message">${highlight(log.msg || "", keyword)}</p>
      <button type="button" class="card-link" data-log-index="${index}" data-result-index="${index}">すべてのフィールドを表示 <span>→</span></button>
    </article>`;
}

function searchFilterLink(field, value, fallback, currentParams) {
  if (!value) return `<span>${escapeHtml(fallback)}</span>`;
  const params = new URLSearchParams(currentParams || "");
  params.set(field, value);
  params.set("page", "1");
  return `<a class="result-filter-link" href="/search?${escapeHtml(params.toString())}" data-route title="${escapeHtml(value)}で絞り込む">${escapeHtml(value)}</a>`;
}

function filterBadge(type, currentParams) {
  const value = type || "unknown";
  if (!type || type === "unknown") return `<span class="log-type">${escapeHtml(value)}</span>`;
  const params = new URLSearchParams(currentParams || "");
  params.set("log_type", type);
  params.set("page", "1");
  return `<a class="log-type log-type-${escapeHtml(type)}" href="/search?${escapeHtml(params.toString())}" data-route title="${escapeHtml(type)}で絞り込む">${escapeHtml(type)}</a>`;
}

function showLogDetail(index) {
  const log = currentResults[index];
  if (!log) return;
  dialogTitle.textContent = `${log.host || "unknown host"} / ${log.program || "unknown program"}`;
  const preferred = ["display_time", "log_type", "host", "program", "msg", "severity", "index", "id"];
  const keys = [...preferred.filter((key) => key in log), ...Object.keys(log).filter((key) => !preferred.includes(key) && key !== "score")];
  dialogBody.innerHTML = `
    <dl class="log-fields">${keys.map((key) => `<div><dt>${escapeHtml(key)}</dt><dd>${renderValue(log[key])}</dd></div>`).join("")}</dl>
    <div class="raw-json"><div><strong>Raw JSON</strong><button type="button" data-copy-json>コピー</button></div><pre>${escapeHtml(JSON.stringify(log, null, 2))}</pre></div>`;
  dialogBody.querySelector("[data-copy-json]").addEventListener("click", async (event) => {
    await navigator.clipboard.writeText(JSON.stringify(log, null, 2));
    event.currentTarget.textContent = "コピーしました";
  });
  logDialog.showModal();
}

function renderValue(value) {
  if (value == null || value === "") return "<span class=\"muted\">—</span>";
  return `<span>${escapeHtml(typeof value === "object" ? JSON.stringify(value) : value)}</span>`;
}

async function renderHealth() {
  document.title = "稼働状況 | Elastic Log Explorer";
  apiLink.href = `/health/${selectedBackend}`;
  app.innerHTML = `
    <section class="health-page">
      <div class="health-page-header"><div><p class="eyebrow">SYSTEM HEALTH</p><h1>稼働状況</h1><p>6つのバックエンドとElasticsearchへの接続状態を確認します。</p></div><button class="button-secondary" type="button" data-health-refresh>↻ 今すぐ更新</button></div>
      <div class="health-summary"><span class="health-dot is-checking"></span><strong data-health-summary>確認中…</strong><time data-health-updated></time></div>
      <div class="health-grid">${Object.entries(BACKENDS).map(([id, backend]) => healthCard(id, backend)).join("")}</div>
    </section>`;
  await updateHealth();
  healthTimer = window.setInterval(updateHealth, 5000);
}

function healthCard(id, backend) {
  return `<article class="health-card is-checking" data-health-card="${id}"><div class="health-card-top"><span class="service-icon" style="--service-color:${backend.color}">${backend.label.slice(0, 1)}</span><span class="health-badge">確認中</span></div><h2>${escapeHtml(backend.label)}</h2><p class="health-message">接続状態を確認しています。</p><dl><div><dt>Backend</dt><dd data-backend-state>—</dd></div><div><dt>Elasticsearch</dt><dd data-es-state>—</dd></div><div><dt>応答時間</dt><dd data-latency>—</dd></div><div><dt>Version</dt><dd data-version>—</dd></div><div><dt>Index</dt><dd data-index>—</dd></div></dl></article>`;
}

async function updateHealth() {
  if (location.pathname !== "/health") return;
  const results = await Promise.all(Object.keys(BACKENDS).map(checkHealth));
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

async function checkHealth(id) {
  const started = performance.now();
  try {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), 4000);
    const response = await fetch(`/health/${id}`, { cache: "no-store", signal: controller.signal });
    window.clearTimeout(timer);
    const payload = await response.json();
    return { id, ok: response.ok && payload.ok === true, latency: Math.round(performance.now() - started), payload };
  } catch (error) {
    return { id, ok: false, latency: Math.round(performance.now() - started), error: error.name === "AbortError" ? "タイムアウト" : error.message };
  }
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

function stopHealth() {
  window.clearInterval(healthTimer);
  healthTimer = undefined;
}

function pagination(params, page, totalPages) {
  if (totalPages <= 1) return "";
  const link = (target, label) => {
    const next = new URLSearchParams(params);
    next.set("page", target);
    return `<a href="/search?${next}" data-route>${label}</a>`;
  };
  return `<nav class="pagination">${page > 1 ? link(page - 1, "← 前へ") : "<span class=\"disabled\">← 前へ</span>"}<span>${page} / ${totalPages}</span>${page < totalPages ? link(page + 1, "次へ →") : "<span class=\"disabled\">次へ →</span>"}</nav>`;
}

async function api(path, params = {}) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value != null && String(value) !== "") query.set(key, value);
  });
  const response = await fetch(apiPath(`${path}${query.size ? `?${query}` : ""}`), { headers: { Accept: "application/json" } });
  let payload;
  try {
    payload = await response.json();
  } catch {
    throw new Error("バックエンドから想定外のレスポンスが返されました。");
  }
  if (!response.ok) throw new Error(payload.error || "検索に失敗しました。");
  return payload;
}

function apiPath(path) {
  return `/api/${selectedBackend}${path}`;
}

function highlight(value, keyword) {
  const text = escapeHtml(value);
  if (!keyword) return text;
  const escapedKeyword = escapeRegExp(escapeHtml(keyword));
  return text.replace(new RegExp(`(${escapedKeyword})`, "gi"), "<mark>$1</mark>");
}

function downloadCsv(logs) {
  const fields = ["display_time", "log_type", "host", "program", "msg", "severity", "index", "id"];
  const csv = [fields, ...logs.map((log) => fields.map((field) => log[field] ?? ""))]
    .map((row) => row.map((value) => `"${String(value).replaceAll("\"", "\"\"")}"`).join(","))
    .join("\r\n");
  const url = URL.createObjectURL(new Blob([`\uFEFF${csv}\r\n`], { type: "text/csv;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = `elastic-logs-${new Date().toISOString().replace(/\D/g, "").slice(0, 14)}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}

function renderError(message) {
  app.innerHTML = `<section class="error-page"><p class="eyebrow">APPLICATION ERROR</p><h1>画面を表示できませんでした</h1><p>${escapeHtml(message)}</p><a class="button-secondary" href="/" data-route>トップページへ戻る</a></section>`;
}

function emptyState(title, description) {
  return `<div class="empty-state"><h3>${title}</h3><p>${description}</p></div>`;
}

function positiveInt(value, fallback) {
  const number = Number.parseInt(value, 10);
  return Number.isFinite(number) && number > 0 ? number : fallback;
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function escapeHtml(value) {
  const element = document.createElement("span");
  element.textContent = String(value ?? "");
  return element.innerHTML;
}

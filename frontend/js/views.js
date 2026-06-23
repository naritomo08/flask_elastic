import { escapeHtml, highlight } from "./utils.js";

export function searchForm(values = {}, hero = false) {
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
          <label><span>Host</span><input name="host" value="${escapeHtml(values.host || "")}" placeholder="elastic1 または /web.*/"></label>
          <label><span>Program</span><input name="program" value="${escapeHtml(values.program || "")}" placeholder="sshd または /ssh.*/"></label>
          <label><span>表示件数</span><select name="size">${[10, 20, 50, 100].map((value) => option(String(value), String(values.size || 20), `${value}件`)).join("")}</select></label>
        </div>
        <div class="filter-actions"><button type="reset" class="button-ghost">すべての条件をクリア</button></div>
      </details>
    </form>`;
}

export function recentLogsMarkup(logs) {
  return logs.length
    ? `<div class="log-grid">${logs.map((log, index) => logCard(log, index)).join("")}</div>`
    : emptyState("ログがありません", "Elasticsearchの接続先とインデックスを確認してください。");
}

export function resultCard(log, index, keyword, params) {
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
      <button type="button" class="card-link" data-log-index="${index}">すべてのフィールドを表示 <span>→</span></button>
    </article>`;
}

export function healthCard(id, backend) {
  return `<article class="health-card is-checking" data-health-card="${id}"><div class="health-card-top"><span class="service-icon" style="--service-color:${backend.color}">${backend.label.slice(0, 1)}</span><span class="health-badge">確認中</span></div><h2>${escapeHtml(backend.label)}</h2><p class="health-message">接続状態を確認しています。</p><dl><div><dt>Backend</dt><dd data-backend-state>—</dd></div><div><dt>Elasticsearch</dt><dd data-es-state>—</dd></div><div><dt>応答時間</dt><dd data-latency>—</dd></div><div><dt>Version</dt><dd data-version>—</dd></div><div><dt>Index</dt><dd data-index>—</dd></div></dl></article>`;
}

export function pagination(params, page, totalPages) {
  if (totalPages <= 1) return "";
  const link = (target, label) => {
    const next = new URLSearchParams(params);
    next.set("page", target);
    return `<a href="/search?${next}" data-route>${label}</a>`;
  };
  return `<nav class="pagination">${page > 1 ? link(page - 1, "← 前へ") : "<span class=\"disabled\">← 前へ</span>"}<span>${page} / ${totalPages}</span>${page < totalPages ? link(page + 1, "次へ →") : "<span class=\"disabled\">次へ →</span>"}</nav>`;
}

export function emptyState(title, description) {
  return `<div class="empty-state"><h3>${title}</h3><p>${description}</p></div>`;
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
      <button type="button" class="card-link" data-log-index="${index}">ログ詳細を見る <span>→</span></button>
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

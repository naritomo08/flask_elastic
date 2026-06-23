import { checkBackendHealth, fetchBackendApi } from "./js/api.js";
import { BACKENDS } from "./js/config.js";
import { renderHealth, stopHealth, updateHealth } from "./js/health-page.js";
import { showLogDetail } from "./js/log-dialog.js";
import { downloadCsv, escapeHtml, positiveInt } from "./js/utils.js";
import {
  emptyState,
  pagination,
  recentLogsMarkup,
  resultCard,
  searchForm
} from "./js/views.js";

const app = document.querySelector("#app");
const backendSelect = document.querySelector("#backend-select");
const logDialog = document.querySelector("#log-dialog");
const dialogBody = document.querySelector("#dialog-body");
const dialogTitle = document.querySelector("#dialog-title");
let selectedBackend = localStorage.getItem("elastic-log-backend") || "flask";
let currentResults = [];
let availabilityTimer;
let availabilityUpdateInFlight = false;
let homeTimer;
let homeUpdateInFlight = false;

if (!BACKENDS[selectedBackend]) selectedBackend = "flask";

if (isPageReload() && location.pathname !== "/") {
  history.replaceState({}, "", "/");
}

window.addEventListener("popstate", renderRoute);
backendSelect.addEventListener("change", async () => {
  if (!backendSelect.value) return;
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
    showLogDetail(currentResults[Number(detailButton.dataset.logIndex)], logDialog, dialogTitle, dialogBody);
    return;
  }

  if (event.target.closest("[data-dialog-close]")) logDialog.close();
  if (event.target.closest("[data-health-refresh]")) await updateHealth(refreshBackendAvailability);
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

initialize();

function isPageReload() {
  const navigation = performance.getEntriesByType?.("navigation")?.[0];
  if (navigation) return navigation.type === "reload";
  return performance.navigation?.type === 1;
}

async function initialize() {
  const results = await refreshBackendAvailability();
  if (results.some((result) => result.ok)) {
    await renderRoute();
  } else {
    renderError("利用可能なバックエンドがありません。復旧を待っています。");
  }
  availabilityTimer = window.setInterval(monitorBackendAvailability, 5000);
}

async function monitorBackendAvailability() {
  if (document.hidden || location.pathname === "/health" || availabilityUpdateInFlight) return;
  availabilityUpdateInFlight = true;
  const previousBackend = selectedBackend;
  try {
    const results = await refreshBackendAvailability();
    const hasAvailableBackend = results.some((result) => result.ok);
    if (!hasAvailableBackend) {
      stopHomeUpdates();
      renderError("利用可能なバックエンドがありません。復旧を待っています。");
    } else if (selectedBackend !== previousBackend || document.querySelector(".error-page")) {
      app.innerHTML = `<div class="loading-state">${escapeHtml(BACKENDS[selectedBackend].label)}へ切り替え中…</div>`;
      await renderRoute();
    }
  } finally {
    availabilityUpdateInFlight = false;
  }
}

async function refreshBackendAvailability() {
  const results = await Promise.all(Object.keys(BACKENDS).map(checkBackendHealth));
  const availableBackendIds = results.filter((result) => result.ok).map((result) => result.id);

  if (availableBackendIds.length && !availableBackendIds.includes(selectedBackend)) {
    selectedBackend = availableBackendIds[0];
    localStorage.setItem("elastic-log-backend", selectedBackend);
  }

  backendSelect.innerHTML = availableBackendIds.length
    ? availableBackendIds.map((id) => `<option value="${id}">${escapeHtml(BACKENDS[id].label)}</option>`).join("")
    : `<option value="">利用可能なBackendなし</option>`;
  backendSelect.disabled = availableBackendIds.length === 0;
  backendSelect.value = availableBackendIds.length ? selectedBackend : "";
  return results;
}

async function renderRoute() {
  stopHealth();
  stopHomeUpdates();
  window.scrollTo({ top: 0 });
  try {
    if (location.pathname === "/health") {
      await renderHealth(app, refreshBackendAvailability);
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
  app.innerHTML = `
    <section class="hero">
      <p class="eyebrow">OPERATIONAL LOG DISCOVERY</p>
      <h1>必要なログへ、<br>すばやく辿り着く。</h1>
      <p class="hero-copy">Elasticsearchに蓄積されたsyslog・authlogを横断検索できます。HOST・PROGRAMは完全一致のほか、<code>/web.*/</code> のように囲むと正規表現で検索できます。</p>
      <div class="log-total" aria-label="現在のログ総量">
        <span>現在のログ総量</span>
        <strong data-home-total>${total.toLocaleString("ja-JP")}<small> 件</small></strong>
      </div>
      ${searchForm({}, true)}
    </section>
    <section class="section">
      <div class="section-heading">
        <div><p class="eyebrow">RECENT EVENTS</p><h2>最近のログ</h2></div>
        <div class="section-heading-actions">
          <span class="live-update-status" data-home-updated>自動更新中</span>
          <a class="text-link" href="/search" data-route>すべて表示 →</a>
        </div>
      </div>
      <div data-home-recent>${recentLogsMarkup(currentResults)}</div>
    </section>`;
  updateHomeTimestamp();
  homeTimer = window.setInterval(updateHome, 5000);
}

async function updateHome() {
  if (location.pathname !== "/" || document.hidden || homeUpdateInFlight) return;
  const backend = selectedBackend;
  homeUpdateInFlight = true;
  try {
    const data = await api("/logs", { page: 1, size: 6 });
    if (location.pathname !== "/" || selectedBackend !== backend) return;

    currentResults = data.results || data.logs || [];
    const total = Number(data.total ?? currentResults.length);
    const totalElement = document.querySelector("[data-home-total]");
    const recentElement = document.querySelector("[data-home-recent]");
    if (totalElement) totalElement.innerHTML = `${total.toLocaleString("ja-JP")}<small> 件</small>`;
    if (recentElement) recentElement.innerHTML = recentLogsMarkup(currentResults);
    updateHomeTimestamp();
  } catch {
    const updated = document.querySelector("[data-home-updated]");
    if (updated) {
      updated.textContent = "更新に失敗しました";
      updated.classList.add("is-error");
    }
  } finally {
    homeUpdateInFlight = false;
  }
}

function updateHomeTimestamp() {
  const updated = document.querySelector("[data-home-updated]");
  if (!updated) return;
  updated.textContent = `自動更新 ${new Date().toLocaleTimeString("ja-JP")}`;
  updated.classList.remove("is-error");
}

function stopHomeUpdates() {
  window.clearInterval(homeTimer);
  homeTimer = undefined;
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

async function api(path, params = {}) {
  return fetchBackendApi(selectedBackend, path, params);
}

function renderError(message) {
  app.innerHTML = `<section class="error-page"><p class="eyebrow">APPLICATION ERROR</p><h1>画面を表示できませんでした</h1><p>${escapeHtml(message)}</p><a class="button-secondary" href="/" data-route>トップページへ戻る</a></section>`;
}

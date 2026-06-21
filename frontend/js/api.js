export async function fetchBackendApi(backend, path, params = {}) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value != null && String(value) !== "") query.set(key, value);
  });
  const response = await fetch(`/api/${backend}${path}${query.size ? `?${query}` : ""}`, {
    cache: "no-store",
    headers: { Accept: "application/json" }
  });
  let payload;
  try {
    payload = await response.json();
  } catch {
    throw new Error("バックエンドから想定外のレスポンスが返されました。");
  }
  if (!response.ok) throw new Error(payload.error || "検索に失敗しました。");
  return payload;
}

export async function checkBackendHealth(id) {
  const started = performance.now();
  try {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), 4000);
    const response = await fetch(`/health/${id}`, {
      cache: "no-store",
      signal: controller.signal
    });
    window.clearTimeout(timer);
    const payload = await response.json();
    return {
      id,
      ok: response.ok && payload.ok === true,
      latency: Math.round(performance.now() - started),
      payload
    };
  } catch (error) {
    return {
      id,
      ok: false,
      latency: Math.round(performance.now() - started),
      error: error.name === "AbortError" ? "タイムアウト" : error.message
    };
  }
}

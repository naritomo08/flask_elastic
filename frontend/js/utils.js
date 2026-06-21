export function positiveInt(value, fallback) {
  const number = Number.parseInt(value, 10);
  return Number.isFinite(number) && number > 0 ? number : fallback;
}

export function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export function escapeHtml(value) {
  const element = document.createElement("span");
  element.textContent = String(value ?? "");
  return element.innerHTML;
}

export function highlight(value, keyword) {
  const text = escapeHtml(value);
  if (!keyword) return text;
  const escapedKeyword = escapeRegExp(escapeHtml(keyword));
  return text.replace(new RegExp(`(${escapedKeyword})`, "gi"), "<mark>$1</mark>");
}

export function downloadCsv(logs) {
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

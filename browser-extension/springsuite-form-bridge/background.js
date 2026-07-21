const DEFAULT_SETTINGS = Object.freeze({
  enabled: true,
  endpoint: "http://127.0.0.1:8090/api/desktop-helper/browser-dom/snapshot",
  token: ""
});

chrome.runtime.onInstalled.addListener(async () => {
  const current = await chrome.storage.local.get(DEFAULT_SETTINGS);
  await chrome.storage.local.set({
    enabled: current.enabled !== false,
    endpoint: current.endpoint || DEFAULT_SETTINGS.endpoint,
    token: current.token || ""
  });
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (!message || message.type !== "SPRINGSUITE_FORM_SNAPSHOT") {
    return false;
  }
  forwardSnapshot(message.payload, sender)
    .then(sendResponse)
    .catch((error) => sendResponse({ ok: false, code: "bridge_error", message: safeMessage(error) }));
  return true;
});

async function forwardSnapshot(payload, sender) {
  const settings = await chrome.storage.local.get(DEFAULT_SETTINGS);
  if (!settings.enabled) {
    return { ok: false, code: "bridge_disabled", message: "SpringSuite Form Bridge is disabled." };
  }

  const endpoint = validateLocalEndpoint(settings.endpoint);
  const headers = { "Content-Type": "application/json" };
  if (settings.token) {
    headers["X-SpringSuite-Browser-Token"] = settings.token;
  }

  const response = await fetch(endpoint, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
    cache: "no-store",
    credentials: "omit"
  });

  const responseText = await response.text().catch(() => "");
  let body = null;
  try {
    body = responseText ? JSON.parse(responseText) : {};
  } catch (_) {
    body = { ok: response.ok, message: responseText };
  }

  const status = {
    ok: response.ok && body?.ok !== false,
    status: response.status,
    code: body?.code || body?.data?.code || (response.ok ? "ok" : "http_error"),
    message: body?.message || body?.data?.message || response.statusText,
    pageUrl: sender?.tab?.url || payload?.url || "",
    updatedAt: new Date().toISOString()
  };
  await chrome.storage.local.set({ lastBridgeStatus: status });
  return status;
}

function validateLocalEndpoint(rawEndpoint) {
  const value = String(rawEndpoint || DEFAULT_SETTINGS.endpoint).trim();
  const url = new URL(value);
  const localHosts = new Set(["127.0.0.1", "localhost", "[::1]", "::1"]);
  if (url.protocol !== "http:" || !localHosts.has(url.hostname)) {
    throw new Error("Bridge endpoint must use plain HTTP on localhost or 127.0.0.1.");
  }
  if (!url.pathname.endsWith("/api/desktop-helper/browser-dom/snapshot")) {
    throw new Error("Bridge endpoint must target the SpringSuite browser DOM snapshot API.");
  }
  return url.toString();
}

function safeMessage(error) {
  return error && error.message ? String(error.message) : String(error || "Unknown bridge error");
}

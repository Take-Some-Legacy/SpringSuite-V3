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
  if (!message || typeof message.type !== "string") {
    return false;
  }

  let operation;
  switch (message.type) {
    case "SPRINGSUITE_FORM_SNAPSHOT":
      operation = forwardSnapshot(message.payload, sender);
      break;
    case "SPRINGSUITE_COMMAND_POLL":
      operation = pollCommand(message.pageId, message.pageUrl);
      break;
    case "SPRINGSUITE_COMMAND_ACK":
      operation = acknowledgeCommand(message.commandId, message.payload);
      break;
    default:
      return false;
  }

  operation
    .then(sendResponse)
    .catch((error) => sendResponse({ ok: false, code: "bridge_error", message: safeMessage(error) }));
  return true;
});

async function forwardSnapshot(payload, sender) {
  const settings = await settingsOrThrow();
  const endpoint = validateLocalEndpoint(settings.endpoint);
  const response = await fetchJson(endpoint, {
    method: "POST",
    headers: bridgeHeaders(settings),
    body: JSON.stringify(payload),
    cache: "no-store",
    credentials: "omit"
  });

  const status = {
    ok: response.ok && response.body?.ok !== false,
    status: response.status,
    code: response.body?.code || response.body?.data?.code || (response.ok ? "ok" : "http_error"),
    message: response.body?.message || response.body?.data?.message || response.statusText,
    pageUrl: sender?.tab?.url || payload?.url || "",
    updatedAt: new Date().toISOString()
  };
  await chrome.storage.local.set({ lastBridgeStatus: status });
  return status;
}

async function pollCommand(pageId, pageUrl) {
  const settings = await settingsOrThrow();
  if (!pageId || !pageUrl) {
    return { ok: false, code: "page_identity_missing", message: "pageId and pageUrl are required." };
  }

  const endpoint = commandEndpoint(settings.endpoint, "/api/desktop-helper/browser-dom/commands/next");
  endpoint.searchParams.set("pageId", String(pageId));
  endpoint.searchParams.set("url", String(pageUrl));
  const response = await fetchJson(endpoint, {
    method: "GET",
    headers: bridgeHeaders(settings),
    cache: "no-store",
    credentials: "omit"
  });
  if (!response.ok || response.body?.ok === false) {
    return {
      ok: false,
      code: response.body?.code || "command_poll_failed",
      message: response.body?.message || response.statusText,
      command: null
    };
  }
  return { ok: true, code: "ok", command: response.body?.data || null };
}

async function acknowledgeCommand(commandId, payload) {
  const settings = await settingsOrThrow();
  if (!commandId) {
    return { ok: false, code: "command_id_missing", message: "commandId is required." };
  }
  const endpoint = commandEndpoint(
    settings.endpoint,
    `/api/desktop-helper/browser-dom/commands/${encodeURIComponent(String(commandId))}/ack`
  );
  const response = await fetchJson(endpoint, {
    method: "POST",
    headers: bridgeHeaders(settings),
    body: JSON.stringify(payload || {}),
    cache: "no-store",
    credentials: "omit"
  });
  return {
    ok: response.ok && response.body?.ok !== false,
    status: response.status,
    code: response.body?.code || response.body?.data?.code || (response.ok ? "ok" : "command_ack_failed"),
    message: response.body?.message || response.body?.data?.message || response.statusText
  };
}

async function settingsOrThrow() {
  const settings = await chrome.storage.local.get(DEFAULT_SETTINGS);
  if (!settings.enabled) {
    throw new Error("SpringSuite Form Bridge is disabled.");
  }
  return settings;
}

function bridgeHeaders(settings) {
  const headers = { "Content-Type": "application/json" };
  if (settings.token) {
    headers["X-SpringSuite-Browser-Token"] = settings.token;
  }
  return headers;
}

async function fetchJson(url, options) {
  const response = await fetch(url, options);
  const responseText = await response.text().catch(() => "");
  let body;
  try {
    body = responseText ? JSON.parse(responseText) : {};
  } catch (_) {
    body = { ok: response.ok, message: responseText };
  }
  return { ok: response.ok, status: response.status, statusText: response.statusText, body };
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
  return url;
}

function commandEndpoint(rawEndpoint, pathname) {
  const url = validateLocalEndpoint(rawEndpoint);
  url.pathname = pathname;
  url.search = "";
  url.hash = "";
  return url;
}

function safeMessage(error) {
  return error && error.message ? String(error.message) : String(error || "Unknown bridge error");
}

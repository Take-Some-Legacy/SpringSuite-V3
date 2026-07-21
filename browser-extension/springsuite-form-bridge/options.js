const DEFAULTS = {
  enabled: true,
  endpoint: "http://127.0.0.1:8090/api/desktop-helper/browser-dom/snapshot",
  token: ""
};

const enabled = document.getElementById("enabled");
const endpoint = document.getElementById("endpoint");
const token = document.getElementById("token");
const status = document.getElementById("status");

document.getElementById("save").addEventListener("click", save);
restore();

async function restore() {
  const settings = await chrome.storage.local.get({ ...DEFAULTS, lastBridgeStatus: null });
  enabled.checked = settings.enabled !== false;
  endpoint.value = settings.endpoint || DEFAULTS.endpoint;
  token.value = settings.token || "";
  renderStatus(settings.lastBridgeStatus);
}

async function save() {
  try {
    const normalized = validateEndpoint(endpoint.value);
    await chrome.storage.local.set({
      enabled: enabled.checked,
      endpoint: normalized,
      token: token.value.trim()
    });
    endpoint.value = normalized;
    status.textContent = "Saved.";
  } catch (error) {
    status.textContent = error.message || String(error);
  }
}

function validateEndpoint(raw) {
  const url = new URL(String(raw || "").trim());
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "[::1]", "::1"].includes(url.hostname)) {
    throw new Error("Endpoint must use HTTP on localhost or 127.0.0.1.");
  }
  if (!url.pathname.endsWith("/api/desktop-helper/browser-dom/snapshot")) {
    throw new Error("Endpoint path must end with /api/desktop-helper/browser-dom/snapshot.");
  }
  return url.toString();
}

function renderStatus(value) {
  if (!value) {
    status.textContent = "No snapshot has been sent yet.";
    return;
  }
  status.textContent = `${value.updatedAt || ""}\n${value.ok ? "Connected" : "Error"}: ${value.message || value.code || "unknown"}`;
}

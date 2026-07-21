# SpringSuite Form Bridge

Manifest V3 extension for Chromium-based browsers. It recognizes real HTML `<form>` elements, sends a privacy-preserving structure snapshot to the local SpringSuite runtime, and executes a short-lived fill command only after the operator clicks **«Вставить»** in the SpringSuite suggestion window.

## Captured

- sanitized page URL and title;
- form `name`, `action`, `method`, autocomplete and validation metadata;
- input/select/textarea type, label, name, placeholder, required/focused/disabled/read-only state;
- select option labels;
- submit controls and approximate screen bounds;
- a boolean `valuePresent` so SpringSuite does not propose overwriting populated fields.

## Never captured

- entered text values;
- passwords, tokens, card values or uploaded file names;
- selected option indexes;
- cookies, local/session storage or page source.

The server removes query strings, URL fragments and user-info before storing a snapshot or writing logs.

## Operator-confirmed insertion

When SpringSuite finds values in `suite.desktop-helper.agent.autofill-profile`, the desktop suggestion window shows the exact non-sensitive text proposed for each field. Nothing is inserted until the operator clicks **«Вставить»**.

The command channel:

- accepts only direct loopback requests with the bridge token;
- binds every command to the current browser `pageId` and sanitized page URL;
- expires commands after 20 seconds by default;
- preserves fields that already contain a value;
- refuses password, token, payment, banking, government-ID and file fields;
- dispatches native `input` and `change` events for React/Vue/Angular-compatible forms;
- never clicks a submit button and never calls `form.submit()`.

## Install

1. Configure `SPRINGSUITE_BROWSER_DOM_TOKEN` and start SpringSuite on `127.0.0.1:8090`.
2. Open `browser://extensions`, `chrome://extensions`, or `edge://extensions`.
3. Enable **Developer mode**.
4. Choose **Load unpacked** and select this directory.
5. Open extension options and paste the same bridge token.
6. Change the endpoint only when SpringSuite uses a different local port.
7. After replacing extension files, press **Reload** on the extension card.

The extension accepts only plain HTTP endpoints on `localhost`, `127.0.0.1`, or `::1`, targeting the fixed SpringSuite snapshot route.

Runtime endpoints:

```text
GET  /api/desktop-helper/browser-dom/status
POST /api/desktop-helper/browser-dom/snapshot
GET  /api/desktop-helper/browser-dom/commands/next
POST /api/desktop-helper/browser-dom/commands/{commandId}/ack
```

All endpoints are direct-loopback-only and token protected. See [`docs/integrations/browser-form-bridge.md`](../../docs/integrations/browser-form-bridge.md) for configuration and verification.

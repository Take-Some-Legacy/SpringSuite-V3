# SpringSuite Form Bridge

Manifest V3 extension for Chromium-based browsers. It recognizes real HTML `<form>` elements and form-associated controls, then sends a privacy-preserving snapshot to the local SpringSuite runtime.

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

## Install

1. Configure `SPRINGSUITE_BROWSER_DOM_TOKEN` and start SpringSuite on `127.0.0.1:8090`.
2. Open `chrome://extensions` or `edge://extensions`.
3. Enable **Developer mode**.
4. Choose **Load unpacked** and select this directory.
5. Open extension options and paste the same bridge token.
6. Change the endpoint only when SpringSuite uses a different local port.

The extension accepts only plain HTTP endpoints on `localhost`, `127.0.0.1`, or `::1`, targeting the fixed SpringSuite snapshot route.

Status endpoint:

```text
GET /api/desktop-helper/browser-dom/status
```

Snapshot endpoint:

```text
POST /api/desktop-helper/browser-dom/snapshot
```

Both endpoints are direct-loopback-only and token protected. See `docs/browser-form-bridge.md` in the SpringSuite repository for configuration and verification.

# Browser Form Bridge

SpringSuite Form Bridge adds semantic recognition of real HTML `<form>` elements to `suite-desktop-helper`. The browser extension reads DOM structure in the active page and sends a bounded, privacy-preserving snapshot to the local SpringSuite runtime.

## Supported form semantics

The bridge recognizes:

- native `<form>` elements and form-associated controls;
- orphan `input`, `select`, and `textarea` controls as a synthetic page form;
- `action`, `method`, `name`, `autocomplete`, `enctype`, `target`, and `novalidate`;
- labels from native `<label>`, `aria-label`, `aria-labelledby`, placeholder, name, and id;
- input type and accessibility role;
- required, focused, visible, disabled, and read-only states;
- select option labels;
- submit controls;
- approximate screen bounds for overlay placement;
- whether a control already contains a value, represented only by `valuePresent`.

Input values are never transported. The bridge does not send passwords, entered text, selected option indexes, uploaded file names, cookies, local storage, session storage, or page source. URL query strings, fragments, and user-info are removed by the server before the snapshot enters agent context or logs.

## Runtime endpoints

```text
POST /api/desktop-helper/browser-dom/snapshot
GET  /api/desktop-helper/browser-dom/status
```

Both endpoints require:

1. a direct loopback connection from `127.0.0.1`, `localhost`, or `::1`;
2. no forwarding/proxy headers;
3. `X-SpringSuite-Browser-Token` matching the configured bridge token.

The loopback/proxy guard prevents a Cloudflare Tunnel that exposes the main SpringSuite HTTP port from exposing the DOM ingest surface. The token remains mandatory as a second boundary.

## Configure the token

Generate a random token in PowerShell:

```powershell
$tokenBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($tokenBytes)
$rng.Dispose()
$env:SPRINGSUITE_BROWSER_DOM_TOKEN = ($tokenBytes | ForEach-Object { $_.ToString("x2") }) -join ""
```

Start SpringSuite from the same PowerShell session. Alternatively, provide the token through the process/service environment.

Configuration:

```yaml
suite:
  desktop-helper:
    browser-dom:
      enabled: true
      require-token: true
      token: ${SPRINGSUITE_BROWSER_DOM_TOKEN:}
      max-snapshot-age: 20s
      max-future-skew: 30s
      max-forms: 64
      max-fields-per-form: 256
      max-options-per-field: 100
      allowed-schemes:
        - http
        - https
```

When `require-token` is true and the token is empty, the service intentionally rejects all snapshots with `browser_dom_token_unconfigured`.

## Install the Chromium extension

The unpacked extension is stored at:

```text
browser-extension/springsuite-form-bridge
```

Installation:

1. Start SpringSuite locally.
2. Open `chrome://extensions` or `edge://extensions`.
3. Enable **Developer mode**.
4. Select **Load unpacked**.
5. Select `browser-extension/springsuite-form-bridge`.
6. Open extension options.
7. Set the local endpoint, normally `http://127.0.0.1:8090/api/desktop-helper/browser-dom/snapshot`.
8. Paste the same bridge token configured in SpringSuite.

The extension accepts only plain HTTP loopback endpoints. It refuses remote hosts, HTTPS origins, and arbitrary API paths.

## Agent behavior

A fresh browser snapshot has priority over Windows UI Automation while its snapshot TTL remains valid. When it expires, the desktop agent falls back to native UI Automation automatically.

Browser DOM snapshots can be used for:

- form detection;
- validation and safety hints;
- field classification;
- deterministic fill planning from the local autofill profile;
- operator overlay positioning.

DOM mutation and submission are intentionally disabled. `BrowserDomBridgeAdapter` advertises recognition capabilities only and returns `browser_dom_write_disabled` for write actions. This prevents a recognized form from silently becoming an executable browser automation surface.

## Verify

With SpringSuite running and the token set:

```powershell
$headers = @{ "X-SpringSuite-Browser-Token" = $env:SPRINGSUITE_BROWSER_DOM_TOKEN }
Invoke-RestMethod -Headers $headers http://127.0.0.1:8090/api/desktop-helper/browser-dom/status
```

Expected status after opening a page containing a form:

- `acceptedSnapshots` increases;
- `lastCode` becomes `ok`;
- `lastFieldCount` is greater than zero;
- desktop-agent status reports `externalSnapshotFresh=true`.

## Failure codes

| Code | Meaning |
|---|---|
| `browser_dom_loopback_required` | Request did not arrive directly over loopback or contained proxy headers. |
| `browser_dom_unauthorized` | Token is missing or incorrect. |
| `browser_dom_token_unconfigured` | Token is required but the server token is empty. |
| `browser_dom_snapshot_stale` | Browser timestamp is outside the accepted age. |
| `browser_dom_snapshot_future` | Browser clock is too far ahead. |
| `browser_dom_form_limit` | Payload exceeds the configured form count. |
| `browser_dom_form_missing` | No recognizable form was supplied. |
| `browser_dom_fields_missing` | Selected form has no visible recognizable controls. |
| `browser_dom_write_disabled` | A caller attempted a DOM write/submit action. |

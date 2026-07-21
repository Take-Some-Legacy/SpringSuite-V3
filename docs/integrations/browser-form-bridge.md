# Browser Form Bridge

SpringSuite Form Bridge adds semantic recognition and operator-confirmed filling of real HTML `<form>` elements to `suite-desktop-helper`. The browser extension sends bounded DOM structure to the local runtime. SpringSuite builds a deterministic fill plan from the local autofill profile and shows the exact proposed text in its desktop suggestion window.

No page field is changed until the operator presses **«Вставить»**.

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

Input values are never transported from the page to SpringSuite. The bridge does not send passwords, entered text, selected option indexes, uploaded file names, cookies, local storage, session storage, or page source. URL query strings, fragments, and user-info are removed before the snapshot enters agent context or logs.

## Fill workflow

1. The extension sends a structural form snapshot.
2. SpringSuite matches fields against `suite.desktop-helper.agent.autofill-profile`.
3. The desktop overlay displays every proposed `label: value` pair.
4. The operator reviews the proposal, may drag the notification window by its title, and clicks **«Вставить»**.
5. SpringSuite queues one short-lived command bound to the current `pageId`, page URL, and snapshot.
6. The extension receives the command, validates the page again, fills permitted controls, and emits native `input` and `change` events.
7. The extension acknowledges filled, skipped, and failed field counts.

The bridge never submits the form. The user remains responsible for reviewing the completed form and pressing the site's submit button.

## Runtime endpoints

```text
POST /api/desktop-helper/browser-dom/snapshot
GET  /api/desktop-helper/browser-dom/status
GET  /api/desktop-helper/browser-dom/commands/next?pageId=...&url=...
POST /api/desktop-helper/browser-dom/commands/{commandId}/ack
```

Every endpoint requires:

1. a direct loopback connection from `127.0.0.1`, `localhost`, or `::1`;
2. no forwarding/proxy headers;
3. `X-SpringSuite-Browser-Token` matching the configured bridge token.

The loopback/proxy guard prevents a Cloudflare Tunnel that exposes the main SpringSuite HTTP port from exposing the DOM snapshot or command surfaces. The token remains mandatory as a second boundary.

## Configuration

Generate a random token in PowerShell:

```powershell
$tokenBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($tokenBytes)
$rng.Dispose()
$env:SPRINGSUITE_BROWSER_DOM_TOKEN = ($tokenBytes | ForEach-Object { $_.ToString("x2") }) -join ""
```

Start SpringSuite from the same PowerShell session, or provide the token through the process/service environment.

```yaml
suite:
  desktop-helper:
    browser-dom:
      enabled: true
      require-token: true
      token: ${SPRINGSUITE_BROWSER_DOM_TOKEN:}
      write-enabled: true
      preserve-existing-values: true
      command-ttl: 20s
      max-snapshot-age: 20s
      max-future-skew: 30s
      max-forms: 64
      max-fields-per-form: 256
      max-options-per-field: 100
      allowed-schemes:
        - http
        - https
    agent:
      autofill-profile:
        firstName: Kayla
        lastName: Verner
        email: kayla@example.test
        phone: "+31 000 000 0000"
        company: Kaylas Systems
        city: Amsterdam
        country: Netherlands
```

`write-enabled=false` keeps recognition and planning active but prevents command creation. `preserve-existing-values=true` prevents replacement of fields that contain text, a selected option, or a checked state.

Passwords, passcodes, secrets, tokens, API keys, payment-card data, banking data, government identifiers, one-time codes, and file controls remain manual regardless of the profile.

## Install or update the extension

The unpacked extension is stored at:

```text
browser-extension/springsuite-form-bridge
```

1. Start SpringSuite locally.
2. Open `browser://extensions`, `chrome://extensions`, or `edge://extensions`.
3. Enable **Developer mode**.
4. Select **Load unpacked** and choose the extension directory.
5. Open extension options.
6. Set the endpoint, normally `http://127.0.0.1:8090/api/desktop-helper/browser-dom/snapshot`.
7. Paste the same bridge token configured in SpringSuite.
8. After updating source files, press **Reload** on the extension card.

## Verification

```powershell
$headers = @{ "X-SpringSuite-Browser-Token" = $env:SPRINGSUITE_BROWSER_DOM_TOKEN }
Invoke-RestMethod -Headers $headers http://127.0.0.1:8090/api/desktop-helper/browser-dom/status
```

After opening a page containing a form:

- `acceptedSnapshots` increases;
- `lastCode` becomes `ok`;
- `lastFieldCount` is greater than zero;
- the SpringSuite overlay shows the proposed field values;
- pressing **«Вставить»** fills only the displayed safe values;
- the form is not submitted.

## Safety invariants

- A command is created only after the desktop overlay button is clicked.
- Commands expire and are bound to one `pageId` and sanitized page URL.
- Existing values are preserved by default both in the planner and in the extension executor.
- Sensitive and file fields are blocked in both the planner and the extension.
- Hidden, disabled, read-only, and invisible fields are skipped.
- No submit action is exposed by the command service.
- Raw values are never written to operator logs.

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
| `browser_dom_command_not_found` | The command expired or was already acknowledged. |
| `browser_dom_command_page_mismatch` | The acknowledgement does not match the originating page. |
| `browser_dom_command_failed` | SpringSuite could not create a safe fill command. |

# Architecture

`cmd/suite-cloudflared-wrapper` is a thin process wrapper around the official `cloudflared` binary.

SpringSuite should treat this wrapper as an external sidecar tool:

```text
SpringSuite JVM
  -> suite-cloudflared-wrapper.exe
     -> cloudflared.exe
```

Runtime data policy:

```text
.springsuite/cloudflared/
  cache/
  events.ndjson
  state.json
```

The wrapper does not implement a tunnel protocol itself. It only constrains process environment, logs lifecycle events, detects public trycloudflare URLs, and preserves stdout/stderr.

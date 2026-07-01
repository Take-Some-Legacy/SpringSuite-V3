# suite-cloudflared-wrapper-go

Experimental Go sidecar for SpringSuite cloudflared process control.

## Goals

- Run `cloudflared` with a local runtime/cache directory.
- Keep `HOME`, `USERPROFILE`, `XDG_CONFIG_HOME`, `XDG_CACHE_HOME`, and `CLOUDFLARED_HOME` inside the project/runtime directory.
- Preserve clean stdout/stderr from `cloudflared`.
- Write machine-readable wrapper events to `.springsuite/cloudflared/events.ndjson`.
- Provide simple JSON `doctor` output for Suite integration.

## Usage

```bat
suite-cloudflared-wrapper.exe doctor --url http://localhost:8090
suite-cloudflared-wrapper.exe run --url http://localhost:8090
```

Named tunnel compatibility mode:

```bat
suite-cloudflared-wrapper.exe run --mode run --url http://localhost:8090 --tunnel spring-suite-test
```

Pass extra cloudflared args after `--`:

```bat
suite-cloudflared-wrapper.exe run --url http://localhost:8090 -- --no-autoupdate
```

## Build

```bat
go test ./...
go build -o build/suite-cloudflared-wrapper.exe ./cmd/suite-cloudflared-wrapper
```

Go is intentionally used without external dependencies for a small standalone binary.

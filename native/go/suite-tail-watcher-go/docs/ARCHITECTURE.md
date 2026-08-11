# Architecture

`cmd/suite-tail-watcher` is a standalone Go sidecar.

SpringSuite integration target:

```text
SpringSuite JVM -> suite-tail-watcher.exe -> stdout JSONL/text events
```

Events are intentionally line-oriented for easy Suite parsing:

```json
{"time":"...","type":"file_modified","path":"logs/spring-suite.log","size":1234}
```

The watcher uses polling instead of native FS bindings to keep the binary dependency-free and portable.

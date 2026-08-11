# suite-tail-watcher-go

Experimental Go sidecar for SpringSuite log tailing, file watching and JSONL event streaming.

## Commands

```bat
suite-tail-watcher.exe tail --path logs --lines 100 --follow
suite-tail-watcher.exe watch --path logs --json
suite-tail-watcher.exe stream --path logs
suite-tail-watcher.exe doctor --path logs
```

## Model

- `tail` prints the last N lines of a file. If `--path` is a directory, the newest `.log` file is selected.
- `tail --follow` behaves like `tail -f`.
- `watch` polls files and emits create/modify/delete events.
- `stream` emits JSONL events and can combine file watch + tail output.

No external dependencies.

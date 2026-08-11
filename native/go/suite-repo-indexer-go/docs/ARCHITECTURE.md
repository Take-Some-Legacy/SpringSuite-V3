# Architecture

`cmd/suite-repo-indexer` is a standalone Go sidecar for SpringSuite repository context.

Input sources:

```text
.springsuite/repositories.json
--root PATH
```

Output:

```text
stdout JSON
.springsuite/repo-index.json
```

Index contents:

- repository root and `.git` path
- descriptor presence
- branch/head from `.git/HEAD`
- dataset roots and examples present
- file counts and byte totals
- extension-level byte totals
- SHA-256 samples for files within size limits

SpringSuite integration target:

```text
SpringSuite JVM -> suite-repo-indexer.exe -> JSON stdout/cache
```

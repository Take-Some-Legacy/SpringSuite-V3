# suite-repo-indexer-go

Experimental Go sidecar for SpringSuite repository discovery and dataset indexing.

## Goals

- Read Suite `.springsuite/repositories.json`.
- Scan configured roots for `.git` repositories.
- Build a repository index/cache with file counts, byte counts, language-extension totals and SHA-256 samples.
- Print JSON to stdout for SpringSuite integration.
- Write JSON cache to `.springsuite/repo-index.json` by default.

## Usage

```bat
suite-repo-indexer.exe doctor --repositories C:\path\to\.springsuite\repositories.json
suite-repo-indexer.exe index --root C:\Users\Aiden\Documents\Repos --scan-depth 3
```

Default:

```bat
suite-repo-indexer.exe index
```

## Build

```bat
scripts\build.bat
```

or:

```bat
go test ./...
go build -o build\suite-repo-indexer.exe ./cmd/suite-repo-indexer
```

No external dependencies.

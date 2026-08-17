# cfr-selective-dec

Languages: [简体中文](README.md) | [English](README.en.md)

A CFR-based batch decompiler for local Java auditing. It scans `.jar`, `.war`, class directories, and nested archives, filters classes by package prefix, then decompiles them in fixed-size batches with cache-aware retries.

## Features

- Decompile a single `.jar`, `.war`, classes directory, or a directory tree containing archives and `.class` files.
- Filter targets by one or more package prefixes, or omit filters to decompile every class.
- Handle common archive layouts:
  - `WEB-INF/classes`
  - `WEB-INF/lib/*.jar`
  - `BOOT-INF/classes`
  - `BOOT-INF/lib/*.jar`
- Recursively process nested `.jar` and `.war` files by stream-previewing them and extracting only archives that contain matching classes, or skip them with `--no-nested`.
- Skip archives that JDK `ZipFile` cannot open (for example jars with malformed ZIP64 CEN metadata), emit a `[warn]`, and continue scanning.
- Aggregate classes by top-level Java source within one input source, then process up to `128` source units per group without mixing archives.
- Reuse an existing `.java` only when its source-class, CFR-version, and output-option fingerprint matches.
- Split failed batches directly into single source units before permanent failure.
- Skip duplicate classes that map to the same final `.java` path and record them in `summary.txt`.
- Write `manifest.txt` with one source mapping for each generated `.java` file.
- Use CFR with `--hideutf false` and UTF-8 output by default.

### Performance (1.0.5+)

- **Configurable workers** - `--threads <n>` is shared by top-level archive scanning and queue concurrency; the default is capped at `min(4, CPUs)`.
- **Source isolation** - each JAR, WAR, or class root has its own groups and classpath, preventing same-name classes from crossing archives.
- **Source-unit aggregation** - `InnerClasses`/`EnclosingMethod` attributes identify families without merging genuine top-level `$` classes.
- **Single archive preparation** - each source archive is opened once and its workspace is reused by retries.
- **Balanced groups** - groups are evenly sized without increasing CFR invocation count, avoiding `128+1` tails.
- **Safe cache** - class metadata and decompiler-option fingerprints invalidate stale output automatically.
- **Unit failure isolation** - one damaged class does not block other source units from the same archive.
- **Single-unit retries** - failed groups split directly into source units; binary backoff is not used.
- **Atomic output commits** - generated source is staged beside the target and then moved into place atomically.

### Performance (1.0.4+)

- **Single-pass directory walk** - merged two `Files.walk()` calls into one pass when scanning directories.
- **32 KB IO buffers** - stream copy buffers increased from 8 KB to 32 KB.
- **Streaming recursive delete** - `Files.walkFileTree()` replaces full-path-list collection.
- **Progress reporting** - each queue round prints `progress=completed/total percentage%`.

## Requirements

- JDK 8 or newer.
- Maven 3.6 or newer.

## Build

```bash
mvn clean package
```

The build produces:

```text
target/cfr-selective-dec-1.0.7.jar
target/cfr-selective-dec-1.0.7-with-dependencies.jar
```

`cfr-selective-dec-1.0.7.jar` is the thin jar without bundled dependencies.
`cfr-selective-dec-1.0.7-with-dependencies.jar` is the self-contained runnable artifact with CFR included.

## Quick Start

Decompile a WAR and only keep classes under `com.example`:

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar --input app.war --output out --packages com.example
```

Decompile a directory tree and include every class:

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar --input ./build-output --output out
```

Decompile multiple package prefixes:

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar --input app.jar --output out --packages com.foo,org.demo
```

## Usage

Named arguments:

```text
java -jar cfr-selective-dec-<version>-with-dependencies.jar --input <path> --output <dir> [--packages <prefixes>] [options]
```

Positional arguments:

```text
java -jar cfr-selective-dec-<version>-with-dependencies.jar <input.jar|input.war|input-dir> <output-dir> [package-prefixes...] [options]
```

### Options

| Option | Description |
| --- | --- |
| `-i, --input <path>` | Input `.jar`, `.war`, classes directory, or directory tree to scan. |
| `-o, --output <dir>` | Directory for generated `.java` files, `summary.txt`, and `manifest.txt`. |
| `-p, --packages <prefixes>` | Optional package prefixes. Use commas or semicolons to separate multiple prefixes. |
| `--output-encoding <charset>` | Output encoding for `.java` files. Default: `UTF-8`. |
| `--threads <n>` | Shared worker threads for top-level archive scanning and decompilation. Default: `min(4, CPUs)`. |
| `--no-nested` | Skip nested JAR/WAR files when only application classes are needed. |
| `--keep-temp` | Keep nested archives that were actually extracted, for troubleshooting. |
| `--debug` | Print full exception stack traces and debug logs. |
| `-h, --help` | Show command help. |

### Package Filters

Package prefixes accept dot or slash notation:

```text
com.foo
com.foo,org.bar
com.foo;org.bar
com/foo
```

When using positional arguments, package prefixes can also be separated by spaces:

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar app.jar out com.foo org.bar
```

If `--packages` and positional package prefixes are omitted, all matching `.class` files are decompiled.

### Encoding

Use `--output-encoding` when auditing projects that need a non-UTF-8 source encoding:

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar app.jar out com.example --output-encoding GB18030
```

### Debugging

Use `--debug` to print full stack traces and internal debug messages:

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar --input app.war --output out --debug
```

Use `--keep-temp` when you need to inspect nested archives that were actually extracted:

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar --input app.war --output out --keep-temp
```

## How It Works

1. Scan the input path for `.class`, `.jar`, and `.war` files. Top-level archives in a directory are scanned in parallel using `--threads`.
2. Normalize archive layouts such as `WEB-INF/classes` and `BOOT-INF/classes`. Nested archives are stream-previewed and extracted only when they contain matching classes.
3. Filter class entries by package prefix.
4. Isolate tasks by source archive or class root and aggregate inner classes into top-level source units.
5. Open each archive once and prepare an isolated workspace for its pending classes.
6. Decompile up to `128` source units per group.
7. Split failed batches directly into single source units before permanent failure.

## Summary Report

Each run writes `summary.txt` to the output directory. It includes:

- `group_size`: batch size used by the queue.
- `source_units`: Java source units after inner-class aggregation.
- `queue_tasks`: number of batch tasks submitted.
- `cache_hits`: classes reused through a valid fingerprint.
- `success`: classes with generated or cached `.java` output.
- `failed`: classes left unresolved after retries.
- `completed`: classes that reached a terminal state.
- `total`: unique class tasks after duplicate removal.
- `duplicates_skipped`: duplicate class tasks skipped before decompilation.
- `failed_classes`: unresolved class list.
- `duplicate_classes`: skipped duplicates and the retained source.

## Manifest

Each run writes `manifest.txt` to the output directory. Each line maps a generated Java class to the source class location used for decompilation:

```text
com.example.Main /path/to/app.jar!com.example.Main
com.example.Main1 /path/to/com/example/Main1.class
```

Only classes with an existing non-empty `.java` output are included. Duplicate classes skipped during task collection are not listed separately; the retained source is used.

## Security Notes

The tool handles untrusted archives defensively:

- Archive entry names are validated to reject absolute paths, drive-letter paths, empty path segments, `.`, `..`, and NUL characters.
- Nested archives are stream-previewed by entry name; only archives that contain matching classes are copied to temporary paths. After a matching parent is extracted, `ZipFile` still scans nested archives that preview did not list. Extracted-byte budget is released when an extracted file is discarded.
- Limits are 1,000,000 target classes, depth 16, 10,000 nested archives counted during preview, and 8 GiB extracted bytes.
- A class entry inside an archive is limited to 64 MiB.
- Generated source files are written only under the configured output directory.
- Large files are copied with fixed-size buffers instead of loading them fully into memory.

## Third-party Notices

This project uses [CFR](https://www.benf.org/other/cfr/) through Maven.

CFR is distributed under the MIT License. See `THIRD_PARTY_NOTICES.md`.

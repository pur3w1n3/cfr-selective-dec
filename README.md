# cfr-selective-dec

Languages: [English](README.md) | [简体中文](README.zh-CN.md)

A CFR-based batch decompiler for local Java auditing. It scans `.jar`, `.war`, class directories, and nested archives, filters classes by package prefix, then decompiles them in fixed-size batches with cache-aware retries.

## Features

- Decompile a single `.jar`, `.war`, classes directory, or a directory tree containing archives and `.class` files.
- Filter targets by one or more package prefixes, or omit filters to decompile every class.
- Handle common archive layouts:
  - `WEB-INF/classes`
  - `WEB-INF/lib/*.jar`
  - `BOOT-INF/classes`
  - `BOOT-INF/lib/*.jar`
- Recursively extract and process nested `.jar` and `.war` files.
- Process classes in groups of `128` using a thread pool sized to the available CPU count.
- Reuse existing non-empty `.java` outputs as cache hits.
- Retry classes that did not produce output; if a full round produces no new files, remaining classes are reported as failed.
- Skip duplicate classes that map to the same final `.java` path and record them in `summary.txt`.
- Write `manifest.txt` with one source mapping for each generated `.java` file.
- Use CFR with `--hideutf false` and UTF-8 output by default.

## Requirements

- JDK 8 or newer.
- Maven 3.6 or newer.

## Build

```bash
mvn clean package
```

The build produces:

```text
target/cfr-selective-dec-standalone.jar
target/cfr-selective-dec.jar
```

Both jars are runnable; `cfr-selective-dec.jar` is a convenience copy of the standalone jar.

## Quick Start

Decompile a WAR and only keep classes under `com.example`:

```bash
java -jar target/cfr-selective-dec-standalone.jar --input app.war --output out --packages com.example
```

Decompile a directory tree and include every class:

```bash
java -jar target/cfr-selective-dec-standalone.jar --input ./build-output --output out
```

Decompile multiple package prefixes:

```bash
java -jar target/cfr-selective-dec-standalone.jar --input app.jar --output out --packages com.foo,org.demo
```

## Usage

Named arguments:

```text
java -jar cfr-selective-dec-standalone.jar --input <path> --output <dir> [--packages <prefixes>] [options]
```

Positional arguments:

```text
java -jar cfr-selective-dec-standalone.jar <input.jar|input.war|input-dir> <output-dir> [package-prefixes...] [options]
```

### Options

| Option | Description |
| --- | --- |
| `-i, --input <path>` | Input `.jar`, `.war`, classes directory, or directory tree to scan. |
| `-o, --output <dir>` | Directory for generated `.java` files, `summary.txt`, and `manifest.txt`. |
| `-p, --packages <prefixes>` | Optional package prefixes. Use commas or semicolons to separate multiple prefixes. |
| `--output-encoding <charset>` | Output encoding for `.java` files. Default: `UTF-8`. |
| `--keep-temp` | Keep temporary extracted archives for troubleshooting. |
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
java -jar target/cfr-selective-dec-standalone.jar app.jar out com.foo org.bar
```

If `--packages` and positional package prefixes are omitted, all matching `.class` files are decompiled.

### Encoding

Use `--output-encoding` when auditing projects that need a non-UTF-8 source encoding:

```bash
java -jar target/cfr-selective-dec-standalone.jar app.jar out com.example --output-encoding GB18030
```

### Debugging

Use `--debug` to print full stack traces and internal debug messages:

```bash
java -jar target/cfr-selective-dec-standalone.jar --input app.war --output out --debug
```

Use `--keep-temp` when you need to inspect extracted nested archives:

```bash
java -jar target/cfr-selective-dec-standalone.jar --input app.war --output out --keep-temp
```

## How It Works

1. Scan the input path for `.class`, `.jar`, and `.war` files.
2. Normalize archive layouts such as `WEB-INF/classes` and `BOOT-INF/classes`.
3. Filter class entries by package prefix.
4. Remove duplicate tasks that would produce the same final `.java` file.
5. Decompile classes in groups of `128`.
6. Check the output directory for non-empty `.java` files after each batch.
7. Requeue classes without output until a full round makes no progress.

## Summary Report

Each run writes `summary.txt` to the output directory. It includes:

- `group_size`: batch size used by the queue.
- `queue_tasks`: number of batch tasks submitted.
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
- Nested archives are copied to random temporary paths before processing.
- Generated source files are written only under the configured output directory.
- Large files are copied with fixed-size buffers instead of loading them fully into memory.

## Third-party Notices

This project uses [CFR](https://www.benf.org/other/cfr/) through Maven.

CFR is distributed under the MIT License. See `THIRD_PARTY_NOTICES.md`.

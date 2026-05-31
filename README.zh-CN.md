# cfr-selective-dec

语言：[English](README.md) | [简体中文](README.zh-CN.md)

基于 CFR 的 Java 批量反编译工具，适用于本地代码审计。它可以扫描 `.jar`、`.war`、class 目录和嵌套归档，按包名前缀筛选目标 class，并通过固定大小分组、缓存检查和逐轮重试完成反编译。

## 功能特性

- 支持反编译单个 `.jar`、`.war`、classes 目录，或包含归档与 `.class` 文件的目录树。
- 支持按一个或多个包名前缀筛选；不指定包名前缀时默认反编译全部 class。
- 支持常见归档布局：
  - `WEB-INF/classes`
  - `WEB-INF/lib/*.jar`
  - `BOOT-INF/classes`
  - `BOOT-INF/lib/*.jar`
- 支持递归提取并处理嵌套 `.jar` 和 `.war`。
- 每组 `128` 个 class 批量提交给 CFR，线程池大小等于可用 CPU 数量。
- 已存在且非空的 `.java` 文件会作为缓存命中跳过。
- 未生成产物的 class 会进入下一轮重试；如果某一整轮没有新增产物，剩余 class 会被记录为失败。
- 会跳过映射到同一个最终 `.java` 路径的重复 class，并记录到 `summary.txt`。
- 默认 CFR 参数为 `--hideutf false`，输出编码默认为 UTF-8。

## 环境要求

- JDK 8 或更高版本。
- Maven 3.6 或更高版本。

## 构建

```bash
mvn clean package
```

构建产物：

```text
target/cfr-selective-dec-standalone.jar
target/cfr-selective-dec.jar
```

两个 jar 都可以直接运行；`cfr-selective-dec.jar` 是 standalone jar 的便捷副本。

## 快速开始

反编译 WAR，并只保留 `com.example` 包下的 class：

```bash
java -jar target/cfr-selective-dec-standalone.jar --input app.war --output out --packages com.example
```

反编译目录树中的全部 class：

```bash
java -jar target/cfr-selective-dec-standalone.jar --input ./build-output --output out
```

反编译多个包名前缀：

```bash
java -jar target/cfr-selective-dec-standalone.jar --input app.jar --output out --packages com.foo,org.demo
```

## 使用方法

命名参数：

```text
java -jar cfr-selective-dec-standalone.jar --input <path> --output <dir> [--packages <prefixes>] [options]
```

位置参数：

```text
java -jar cfr-selective-dec-standalone.jar <input.jar|input.war|input-dir> <output-dir> [package-prefixes...] [options]
```

### 参数

| 参数 | 说明 |
| --- | --- |
| `-i, --input <path>` | 输入 `.jar`、`.war`、classes 目录，或需要扫描的目录树。 |
| `-o, --output <dir>` | 生成 `.java` 文件和 `summary.txt` 的输出目录。 |
| `-p, --packages <prefixes>` | 可选包名前缀。多个前缀可用逗号或分号分隔。 |
| `--output-encoding <charset>` | `.java` 文件输出编码。默认：`UTF-8`。 |
| `--keep-temp` | 保留临时提取的嵌套归档，便于排查问题。 |
| `--debug` | 输出完整异常堆栈和调试日志。 |
| `-h, --help` | 显示命令帮助。 |

### 包名前缀

包名前缀支持点号或斜杠格式：

```text
com.foo
com.foo,org.bar
com.foo;org.bar
com/foo
```

使用位置参数时，也可以用空格分隔多个包名前缀：

```bash
java -jar target/cfr-selective-dec-standalone.jar app.jar out com.foo org.bar
```

如果没有提供 `--packages` 或位置参数包名前缀，则默认反编译所有匹配到的 `.class` 文件。

### 输出编码

如果审计项目需要非 UTF-8 源码编码，可以使用 `--output-encoding`：

```bash
java -jar target/cfr-selective-dec-standalone.jar app.jar out com.example --output-encoding GB18030
```

### 调试

使用 `--debug` 输出完整异常堆栈和内部调试信息：

```bash
java -jar target/cfr-selective-dec-standalone.jar --input app.war --output out --debug
```

需要检查临时提取的嵌套归档时，可以使用 `--keep-temp`：

```bash
java -jar target/cfr-selective-dec-standalone.jar --input app.war --output out --keep-temp
```

## 工作方式

1. 扫描输入路径中的 `.class`、`.jar` 和 `.war` 文件。
2. 规范化 `WEB-INF/classes`、`BOOT-INF/classes` 等归档布局。
3. 按包名前缀筛选 class entry。
4. 移除会生成同一个最终 `.java` 文件的重复任务。
5. 按每组 `128` 个 class 批量反编译。
6. 每个批次后检查输出目录中是否存在非空 `.java` 文件。
7. 将没有产物的 class 重新入队，直到某一整轮没有任何新增产物。

## 摘要报告

每次运行都会在输出目录写入 `summary.txt`，包含：

- `group_size`：队列使用的批大小。
- `queue_tasks`：提交的批处理任务数。
- `success`：已生成或命中缓存的 `.java` 文件数量。
- `failed`：多轮重试后仍未完成的 class 数量。
- `completed`：进入终态的 class 数量。
- `total`：去重后的唯一 class 任务数。
- `duplicates_skipped`：反编译前跳过的重复 class 数量。
- `failed_classes`：未成功反编译的 class 列表。
- `duplicate_classes`：跳过的重复项以及被保留的来源。

## 安全说明

工具会防御性处理不可信归档：

- 校验归档 entry name，拒绝绝对路径、盘符路径、空路径段、`.`、`..` 和 NUL 字符。
- 嵌套归档会先复制到随机临时路径再处理。
- 生成的源码文件只会写入配置的输出目录。
- 大文件复制使用固定大小缓冲区，不会整体读入内存。

## 第三方声明

本项目通过 Maven 使用 [CFR](https://www.benf.org/other/cfr/)。

CFR 使用 MIT License。详见 `THIRD_PARTY_NOTICES.md`。

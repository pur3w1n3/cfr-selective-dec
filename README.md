# cfr-selective-dec

语言：[简体中文](README.md) | [English](README.en.md)

基于 CFR 的 Java 批量反编译工具，适用于本地代码审计。它可以扫描 `.jar`、`.war`、class 目录和嵌套归档，按包名前缀筛选目标 class，并通过固定大小分组、缓存检查和逐轮重试完成反编译。

## 功能特性

- 支持反编译单个 `.jar`、`.war`、classes 目录，或包含归档与 `.class` 文件的目录树。
- 支持按一个或多个包名前缀筛选；不指定包名前缀时默认反编译全部 class。
- 支持常见归档布局：
  - `WEB-INF/classes`
  - `WEB-INF/lib/*.jar`
  - `BOOT-INF/classes`
  - `BOOT-INF/lib/*.jar`
- 支持递归处理嵌套 `.jar` 和 `.war`：先流式预扫，仅提取包含匹配 class 的包；也可通过 `--no-nested` 跳过。
- 无法用 JDK `ZipFile` 打开的归档（例如 ZIP64 CEN 异常的 jar）会输出 `[warn]` 并跳过，不中断整体扫描。
- 同一来源内按顶层 Java 源文件聚合 class，每组最多 `128` 个源码单元提交给 CFR；不同归档不会混入同一批次。
- 只有来源 class、CFR 版本和输出参数指纹一致时，已有 `.java` 才会作为缓存命中。
- 未生成产物的批次会直接拆成单个源码单元重试，仍无产物时记录为失败。
- 会跳过映射到同一个最终 `.java` 路径的重复 class，并记录到 `summary.txt`。
- 会生成 `manifest.txt`，记录每个已生成 `.java` 文件对应的来源 class。
- 默认 CFR 参数为 `--hideutf false`，输出编码默认为 UTF-8。

### 性能优化（1.0.5+）

- **可配置线程数** - `--threads <n>` 同时用于顶层归档扫描和队列并发，默认不超过 `min(4, CPUs)`。
- **来源隔离** - 不同 JAR、WAR 或 class 根目录使用独立批次和 classpath，避免同名类串包。
- **源码单元聚合** - 根据 `InnerClasses`/`EnclosingMethod` 属性聚合内部类，顶层 `$` 类不会被误合并。
- **单次归档准备** - 每个来源只打开一次归档并准备待处理 class，重试复用同一工作区。
- **均衡分组** - 在不增加 CFR 调用次数的前提下均分批次，避免 `128+1` 长尾。
- **安全缓存** - 使用 class 元数据与反编译参数指纹复用输出，输入变化后自动重建。
- **单元故障隔离** - 单个损坏 class 不会阻止同一归档中的其他源码单元。
- **单单元重试** - 失败批次直接拆成单个源码单元，不使用二分退避。
- **原子输出** - 生成结果先写同目录临时文件，再原子移动到最终路径。

### 性能优化（1.0.4+）

- **单次目录遍历** - 将 `processDirectory()` 中的两次 `Files.walk()` 合并为单次遍历。
- **32 KB IO 缓冲区** - 流拷贝缓冲区从 8 KB 提升至 32 KB。
- **流式递归删除** - `Files.walkFileTree()` 替代全路径收集后删除。
- **进度报告** - 每轮队列输出 `progress=已完成/总数 百分比%`。

## 环境要求

- JDK 8 或更高版本。
- Maven 3.6 或更高版本。

## 构建

```bash
mvn clean package
```

构建产物：

```text
target/cfr-selective-dec-1.0.7.jar
target/cfr-selective-dec-1.0.7-with-dependencies.jar
```

`cfr-selective-dec-1.0.7.jar` 为不包含依赖的薄 jar；`cfr-selective-dec-1.0.7-with-dependencies.jar` 为包含 CFR 的完整可运行 jar。

## 快速开始

反编译 WAR，并只保留 `com.example` 包下的 class：

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar --input app.war --output out --packages com.example
```

反编译目录树中的全部 class：

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar --input ./build-output --output out
```

反编译多个包名前缀：

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar --input app.jar --output out --packages com.foo,org.demo
```

## 使用方法

命名参数：

```text
java -jar cfr-selective-dec-<version>-with-dependencies.jar --input <path> --output <dir> [--packages <prefixes>] [options]
```

位置参数：

```text
java -jar cfr-selective-dec-<version>-with-dependencies.jar <input.jar|input.war|input-dir> <output-dir> [package-prefixes...] [options]
```

### 参数

| 参数 | 说明 |
| --- | --- |
| `-i, --input <path>` | 输入 `.jar`、`.war`、classes 目录，或需要扫描的目录树。 |
| `-o, --output <dir>` | 生成 `.java` 文件、`summary.txt` 和 `manifest.txt` 的输出目录。 |
| `-p, --packages <prefixes>` | 可选包名前缀。多个前缀可用逗号或分号分隔。 |
| `--output-encoding <charset>` | `.java` 文件输出编码。默认：`UTF-8`。 |
| `--threads <n>` | 顶层归档扫描和反编译共用的工作线程数。默认：`min(4, CPUs)`。 |
| `--no-nested` | 跳过嵌套 JAR/WAR，可显著加快只关注主应用 class 的扫描。 |
| `--keep-temp` | 保留实际提取出的嵌套归档，便于排查问题。 |
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
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar app.jar out com.foo org.bar
```

如果没有提供 `--packages` 或位置参数包名前缀，则默认反编译所有匹配到的 `.class` 文件。

### 输出编码

如果审计项目需要非 UTF-8 源码编码，可以使用 `--output-encoding`：

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar app.jar out com.example --output-encoding GB18030
```

### 调试

使用 `--debug` 输出完整异常堆栈和内部调试信息：

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar --input app.war --output out --debug
```

需要检查实际提取出的嵌套归档时，可以使用 `--keep-temp`：

```bash
java -jar target/cfr-selective-dec-1.0.7-with-dependencies.jar --input app.war --output out --keep-temp
```

## 工作方式

1. 扫描输入路径中的 `.class`、`.jar` 和 `.war` 文件；目录中的顶层归档按 `--threads` 并行扫描。
2. 规范化 `WEB-INF/classes`、`BOOT-INF/classes` 等归档布局；嵌套归档先流式预扫，仅提取包含匹配 class 的包。
3. 按包名前缀筛选 class entry。
4. 按来源归档或 class 根目录隔离任务，并将内部类聚合到顶层源码单元。
5. 每个归档只打开一次，准备该来源待处理 class 的独立工作区。
6. 按每组最多 `128` 个源码单元批量反编译。
7. 将没有产物的批次直接拆成单个源码单元重试。

## 摘要报告

每次运行都会在输出目录写入 `summary.txt`，包含：

- `group_size`：队列使用的批大小。
- `source_units`：聚合内部类后的 Java 源码单元数。
- `queue_tasks`：提交的批处理任务数。
- `cache_hits`：通过有效指纹复用的 class 数量。
- `success`：已生成或命中缓存的 class 数量。
- `failed`：多轮重试后仍未完成的 class 数量。
- `completed`：进入终态的 class 数量。
- `total`：去重后的唯一 class 任务数。
- `duplicates_skipped`：反编译前跳过的重复 class 数量。
- `failed_classes`：未成功反编译的 class 列表。
- `duplicate_classes`：跳过的重复项以及被保留的来源。

## Manifest

每次运行都会在输出目录写入 `manifest.txt`。每一行记录一个已生成 Java 类和用于反编译的来源 class：

```text
com.example.Main /path/to/app.jar!com.example.Main
com.example.Main1 /path/to/com/example/Main1.class
```

只有实际存在且非空的 `.java` 产物会被写入。任务收集阶段跳过的重复 class 不会单独列出；manifest 中使用被保留的来源。

## 安全说明

工具会防御性处理不可信归档：

- 校验归档 entry name，拒绝绝对路径、盘符路径、空路径段、`.`、`..` 和 NUL 字符。
- 嵌套归档先流式预扫条目名，仅将包含匹配 class 的包复制到临时路径；父包因匹配而提取后，仍用 `ZipFile` 补扫预扫未列出的嵌套包。删除未保留的提取文件时归还累计提取预算。
- 限制待反编译目标类数量（100 万）、嵌套深度（16）、嵌套归档数量（10000，预扫时计数）和累计提取大小（8 GiB）。
- 单个归档 class 最大允许 64 MiB，防止异常条目耗尽磁盘。
- 生成的源码文件只会写入配置的输出目录。
- 大文件复制使用固定大小缓冲区，不会整体读入内存。

## 第三方声明

本项目通过 Maven 使用 [CFR](https://www.benf.org/other/cfr/)。

CFR 使用 MIT License。详见 `THIRD_PARTY_NOTICES.md`。

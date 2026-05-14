# cfr-selective-dec

基于 CFR 的批量反编译工具，用于本地代码审计。它会先按包名前缀筛选 class，再生成临时过滤 jar 交给内置 CFR 反编译，避免把大型应用里的全部依赖源码一次性展开。

## 功能

- 支持输入单个 `.jar`、`.war`。
- 支持输入目录，并递归扫描目录里的 `.jar`、`.war` 和 `.class`。
- 支持一个或多个包名前缀，例如 `com.foo,org.bar`。
- WAR 会按层处理：
  - `WEB-INF/classes`
  - `WEB-INF/lib/*.jar`
- Spring Boot fat jar 会按层处理：
  - `BOOT-INF/classes`
  - `BOOT-INF/lib/*.jar`
- 遇到嵌套 `jar/war` 会逐级抽取、逐级筛选、逐级反编译。
- 默认传给 CFR：`--hideutf false --outputencoding UTF-8`。
- 可通过 `--output-encoding GB18030` 等参数改变 `.java` 输出编码。

## 安全加固

工具会处理不可信的 jar/war，因此做了以下防护：

- 不把压缩包内容直接解压到输出目录，只复制匹配 class 或嵌套归档到随机临时目录。
- 校验压缩包 entry name，拒绝绝对路径、盘符路径、空路径、`.`、`..`、空路径段和 NUL 字符，避免 Zip Slip。
- 临时过滤 jar 中的 entry name 也会再次校验。
- 默认异常只打印简要错误，使用 `--debug` 才打印完整堆栈。
- 复制使用固定缓冲区流式处理，不把大文件整体读入内存。

说明：当前不设置固定的项目规模上限，避免误伤大型项目。只要磁盘和 CPU 能承受，就会继续处理。

## 项目结构

```text
src/main/java/com/aq/cfrselect/
  Main.java                         # 程序入口
  archive/ArchiveNames.java         # 归档命名、entry name 校验
  cli/CliOptions.java               # 命令行参数解析
  cli/UsagePrinter.java             # 中英文帮助
  cli/UsageException.java           # 参数错误
  core/SelectiveDecompiler.java     # 扫描、筛选、调用 CFR
  io/IoUtils.java                   # 流复制、临时目录清理
  matching/PackageMatcher.java      # 包名前缀匹配
  model/ClassFileMatch.java         # class 匹配结果
  model/FilterResult.java           # 临时过滤 jar 结果
third_party/cfr/src/                # 内置 CFR 源码
```

构建产物目录：

```text
build/
dist/
```

## 构建

要求：

- JDK 8 或更高版本。
- 推荐设置 `JAVA_HOME` 指向 JDK；或者确保 `javac`、`jar`、`java` 在 `PATH` 中。

构建：

```bat
build.bat
```

产物：

```text
dist\cfr-selective-dec-standalone.jar
dist\cfr-selective-dec.jar
```

两个 jar 内容相同，`cfr-selective-dec.jar` 只是便捷别名。

## 发布

推送 tag 后会自动触发 GitHub Actions，执行 `build.bat`，并基于当前 tag 创建 GitHub Release，同时上传以下产物：

```text
dist\cfr-selective-dec-standalone.jar
dist\cfr-selective-dec.jar
```

示例：

```bat
git tag v1.0.0
git push origin v1.0.0
```

## 使用

位置参数：

```bat
java -jar dist\cfr-selective-dec-standalone.jar app.war out com.example
java -jar dist\cfr-selective-dec-standalone.jar app.jar out com.example,org.demo
java -jar dist\cfr-selective-dec-standalone.jar app-dir out com.example
```

命名参数：

```bat
java -jar dist\cfr-selective-dec-standalone.jar --input app.war --output out --packages com.example,org.demo
java -jar dist\cfr-selective-dec-standalone.jar --input app-dir --output out --packages com.example
```

指定输出编码：

```bat
java -jar dist\cfr-selective-dec-standalone.jar app.jar out com.example --output-encoding GB18030
```

打印完整异常堆栈：

```bat
java -jar dist\cfr-selective-dec-standalone.jar app.jar out com.example --debug
```

使用运行脚本：

```bat
run.bat app.war out com.example
```

## 参数说明

```text
java -jar cfr-selective-dec-standalone.jar <input.jar|input.war|input-dir> <output-dir> <package1[,package2]> [packageN...] [options]
```

- 第一个参数：输入的 `.jar`、`.war` 或目录。
- 第二个参数：反编译输出目录。
- 第三个及后续参数：包名前缀。
- `--output-encoding <charset>`：输出 `.java` 文件编码，默认 `UTF-8`。
- `--keep-temp`：保留临时过滤 jar 和嵌套归档副本，便于排查。
- `--debug`：打印完整异常堆栈。

包名支持逗号、分号或空格分隔：

```text
com.foo
com.foo,org.bar
com.foo;org.bar
com.foo org.bar
```

## 输出结构

输出目录会按来源分层保存。例如输入 `app.war`：

```text
out/
  app/
    WEB-INF/
      classes/
      lib/
    nested/
```

输入目录时会保留相对输入目录的原始结构。例如：

```text
input/
  target/classes/com/demo/App.class
  lib/a.jar

out/
  input/
    target/classes/com/demo/App.java
    lib/a/com/demo/FromJar.java
```

## 第三方声明

本项目内置 CFR 源码，位于 `third_party/cfr`。

CFR 使用 MIT License，详见：

- `third_party/cfr/LICENSE`
- `THIRD_PARTY_NOTICES.md`

## 更新说明

- 添加workflow自动编译

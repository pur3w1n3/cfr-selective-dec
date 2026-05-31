# cfr-selective-dec

基于 CFR 的批量反编译工具，用于本地代码审计。它会先按包名前缀筛选 class，再按固定大小分组进入反编译队列，并通过产物缓存检查逐轮重试未完成的 class。

## 功能

- 支持输入单个 `.jar`、`.war`。
- 支持输入目录，并递归扫描目录里的 `.jar`、`.war` 和 `.class`。
- 支持一个或多个包名前缀，例如 `com.foo,org.bar`；未提供时默认反编译全部 class。
- WAR 会按层处理：
  - `WEB-INF/classes`
  - `WEB-INF/lib/*.jar`
- Spring Boot fat jar 会按层处理：
  - `BOOT-INF/classes`
  - `BOOT-INF/lib/*.jar`
- 遇到嵌套 `jar/war` 会逐级抽取、逐级筛选、逐级反编译。
- 按每组 `128` 个 class 批量交给 CFR 反编译，线程池大小为 CPU 数量。
- 批量反编译后检查输出目录中是否存在非空 `.java` 文件；已生成的 class 视为完成并跳过。
- 未生成产物的 class 会进入下一轮队列；如果整轮没有新增产物，剩余 class 视为无法反编译并写入摘要。
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
  core/SelectiveDecompiler.java     # 队列式批量反编译
  io/IoUtils.java                   # 流复制、临时目录清理
  matching/PackageMatcher.java      # 包名前缀匹配
  model/ClassFileMatch.java         # class 匹配结果
pom.xml                             # Maven 构建配置
```

构建产物目录：

```text
target/
```

## 构建

要求：

- JDK 8 或更高版本。
- Maven 3.6+。
- 推荐设置 `JAVA_HOME` 指向 JDK；并确保 `mvn`、`java` 在 `PATH` 中。

构建：

```bat
mvn clean package
```

产物：

```text
target\cfr-selective-dec-standalone.jar
target\cfr-selective-dec.jar
```

两个 jar 内容相同，`cfr-selective-dec.jar` 只是便捷别名。

## 发布

推送 tag 后会自动触发 GitHub Actions，执行 `mvn -B clean package`，并基于当前 tag 创建 GitHub Release，同时上传以下产物：

```text
target\cfr-selective-dec-standalone.jar
target\cfr-selective-dec.jar
```

示例：

```bat
git tag v1.0.0
git push origin v1.0.0
```

## 使用

位置参数：

```bat
java -jar target\cfr-selective-dec-standalone.jar app.war out com.example
java -jar target\cfr-selective-dec-standalone.jar app.jar out com.example,org.demo
java -jar target\cfr-selective-dec-standalone.jar app-dir out com.example
java -jar target\cfr-selective-dec-standalone.jar app.war out
```

命名参数：

```bat
java -jar target\cfr-selective-dec-standalone.jar --input app.war --output out --packages com.example,org.demo
java -jar target\cfr-selective-dec-standalone.jar --input app-dir --output out --packages com.example
java -jar target\cfr-selective-dec-standalone.jar --input app.war --output out
```

指定输出编码：

```bat
java -jar target\cfr-selective-dec-standalone.jar app.jar out com.example --output-encoding GB18030
```

打印完整异常堆栈：

```bat
java -jar target\cfr-selective-dec-standalone.jar app.jar out com.example --debug
```

## 参数说明

```text
java -jar cfr-selective-dec-standalone.jar <input.jar|input.war|input-dir> <output-dir> [<package1[,package2]> [packageN...]] [options]
```

- 第一个参数：输入的 `.jar`、`.war` 或目录。
- 第二个参数：反编译输出目录。
- 第三个及后续参数：包名前缀；留空时默认反编译全部 class。
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

输出目录按 class 包路径保存，多个来源中相同 fully-qualified class name 会映射到同一个 `.java` 文件。任务收集阶段会按最终输出路径去重，重复项不会进入反编译队列，并会记录在 `summary.txt` 的 `duplicate_classes` 中。

例如输入 `app.war` 中存在 `WEB-INF/classes/com/demo/App.class`：

```text
out/
  com/
    demo/
      App.java
  summary.txt
```

输入目录中的裸 `.class`、`.jar`、`.war` 和嵌套归档也遵循同样规则。例如：

```text
input/
  target/classes/com/demo/App.class
  lib/a.jar

out/
  com/
    demo/
      App.java
      FromJar.java
  summary.txt
```

## 第三方声明

本项目通过 Maven 依赖使用 CFR。

CFR 使用 MIT License，详见：

- `THIRD_PARTY_NOTICES.md`

## 更新说明

- 添加workflow自动编译

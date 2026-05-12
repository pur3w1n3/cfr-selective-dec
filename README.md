# cfr-selective-dec

基于 [CFR](https://www.benf.org/other/cfr/) 的批量反编译工具，用于本地代码审计。

它适合在审计大型 `jar`、`war` 或 Spring Boot fat jar 时，只反编译指定包名下的业务代码，避免把 `WEB-INF/lib`、`BOOT-INF/lib` 里的大量第三方组件源码一起反编译出来。

## 功能

- 支持输入 `.jar` 和 `.war`。
- 支持一个或多个包名前缀，例如 `com.foo,org.bar`。
- WAR 会按层处理：
  - `WEB-INF/classes`
  - `WEB-INF/lib/*.jar`
- Spring Boot fat jar 会按层处理：
  - `BOOT-INF/classes`
  - `BOOT-INF/lib/*.jar`
- 遇到嵌套 `jar/war` 会逐级抽取、逐级筛选、逐级反编译，避免把 jar 嵌套 jar 的结构直接丢给 CFR。
- 先按包名筛选 class，再生成临时过滤 jar，最后调用内置 CFR 反编译。
- CFR 源码已内置在 `third_party/cfr`，构建后是单文件可运行 jar。
- 输出 Java 8 兼容字节码。
- 默认传给 CFR：`--hideutf false --outputencoding UTF-8`，尽量避免中文字符串被输出成 `\uXXXX`，并使用 UTF-8 保存源码。

## 项目结构

```text
.
├── src/main/java/com/aq/cfrselect/Main.java
├── third_party/cfr/src/          # 内置 CFR 源码
├── third_party/cfr/LICENSE       # CFR MIT License
├── third_party/cfr/README-CFR.md
├── build.bat
├── build-standalone.bat
├── run.bat
├── clean.bat
├── THIRD_PARTY_NOTICES.md
└── README.md
```

以下目录是构建产物，默认不会提交到 git：

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

## 使用

位置参数：

```bat
java -jar dist\cfr-selective-dec-standalone.jar app.war out com.example
java -jar dist\cfr-selective-dec-standalone.jar app.jar out com.example,org.demo
```

命名参数：

```bat
java -jar dist\cfr-selective-dec-standalone.jar --input app.war --output out --packages com.example,org.demo
```

使用运行脚本：

```bat
run.bat app.war out com.example
```

## 参数说明

```text
java -jar cfr-selective-dec-standalone.jar <input.jar|input.war> <output-dir> <package1[,package2]> [packageN...]
```

- 第一个参数：输入的 `.jar` 或 `.war` 文件。
- 第二个参数：反编译输出目录。
- 第三个及后续参数：包名前缀。

包名支持逗号、分号或空格分隔：

```text
com.foo
com.foo,org.bar
com.foo;org.bar
com.foo org.bar
```

## 输出结构

输出目录会按来源分层保存，例如：

```text
out/
└── app/
    ├── WEB-INF/
    │   ├── classes/
    │   └── lib/
    └── nested/
```

这样可以区分业务 class、依赖 jar、嵌套 jar 的反编译结果。

## 第三方声明

本项目内置 CFR 源码，位于 `third_party/cfr`。

CFR 使用 MIT License，详见：

- `third_party/cfr/LICENSE`
- `THIRD_PARTY_NOTICES.md`


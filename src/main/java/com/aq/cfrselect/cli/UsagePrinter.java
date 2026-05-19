package com.aq.cfrselect.cli;

public final class UsagePrinter {
    private UsagePrinter() {
    }

    public static void print() {
        System.out.println("Usage / 用法:");
        System.out.println("  java -jar cfr-selective-dec-standalone.jar <input.jar|input.war|input-dir> <output-dir> [<package1[,package2]> [packageN...]]");
        System.out.println("  java -jar cfr-selective-dec-standalone.jar --input app.war --output out --packages com.demo,org.example");
        System.out.println("  java -jar cfr-selective-dec-standalone.jar --input app.war --output out");
        System.out.println("  java -jar cfr-selective-dec-standalone.jar --input app-dir --output out --packages com.demo");
        System.out.println();
        System.out.println("Options / 参数:");
        System.out.println("  -i, --input       Input .jar, .war, classes directory, or directory to scan");
        System.out.println("                    输入 .jar、.war、classes 目录，或需要递归扫描的目录");
        System.out.println("  -o, --output      Output directory / 反编译输出目录");
        System.out.println("  -p, --packages    Optional package prefixes, comma or semicolon separated");
        System.out.println("                    Omit this option to decompile all classes");
        System.out.println("                    包名前缀，支持逗号或分号分隔");
        System.out.println("      --output-encoding <charset>");
        System.out.println("                    Charset used to save .java files, default UTF-8");
        System.out.println("                    .java 文件保存编码，默认 UTF-8");
        System.out.println("      --keep-temp   Keep temporary filtered jars for inspection");
        System.out.println("                    保留临时过滤 jar，便于排查");
        System.out.println("      --debug       Print full exception stack traces / 打印完整异常堆栈");
        System.out.println("  -h, --help        Show this help / 显示帮助");
    }
}

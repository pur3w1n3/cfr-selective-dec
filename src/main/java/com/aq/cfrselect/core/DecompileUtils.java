package com.aq.cfrselect.core;

final class DecompileUtils {
    private DecompileUtils() {
    }

    static String toJavaEntry(String entryName) {
        return entryName.substring(0, entryName.length() - ".class".length()) + ".java";
    }

    static String toClassName(String entryName) {
        String withoutSuffix = entryName.substring(0, entryName.length() - ".class".length());
        return withoutSuffix.replace('/', '.').replace('\\', '.');
    }

}

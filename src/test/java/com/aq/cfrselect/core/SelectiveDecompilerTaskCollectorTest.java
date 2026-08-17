package com.aq.cfrselect.core;

import com.aq.cfrselect.cli.CliOptions;
import com.aq.cfrselect.matching.PackageMatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelectiveDecompilerTaskCollectorTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void collectKeepsSameClassNameFromDifferentArchives() throws Exception {
        File input = temp.newFolder("input");
        File output = new File(temp.getRoot(), "out");
        writeJar(new File(input, "a.jar"), "com/acme/Duplicate.class");
        writeJar(new File(input, "b.jar"), "com/acme/Duplicate.class");
        writeJar(new File(input, "c.jar"), "com/acme/Unique.class");

        CliOptions options = CliOptions.parse(new String[] {
                "--input", input.getAbsolutePath(),
                "--output", output.getAbsolutePath(),
                "--packages", "com.acme",
                "--threads", "2"
        });
        SelectiveDecompilerSummary summary = new SelectiveDecompilerSummary();
        Path tempRoot = temp.newFolder("tmp").toPath();

        SelectiveDecompilerTaskCollector collector = new SelectiveDecompilerTaskCollector(
                options, new PackageMatcher(options.packages), tempRoot, summary);
        List<DecompileTask> tasks = collector.collect();

        assertEquals(3, tasks.size());
        assertEquals(3, summary.matchedClasses.get());
        assertEquals(0, summary.duplicateUnits.get());
        assertEquals(0, summary.duplicateClasses.size());
        assertEquals("com.acme.Duplicate", tasks.get(0).className);
        assertEquals(options.output.resolve("input").resolve("a"), tasks.get(0).outputDir);
        assertEquals("com.acme.Duplicate", tasks.get(1).className);
        assertEquals(options.output.resolve("input").resolve("b"), tasks.get(1).outputDir);
        assertEquals(new File(input, "a.jar").toPath().toAbsolutePath().normalize()
                + "!com.acme.Duplicate", tasks.get(0).sourceLocation);
    }

    @Test
    public void collectMapsBootInfClassesToApplicationClassPath() throws Exception {
        File input = temp.newFolder("input");
        File output = new File(temp.getRoot(), "out");
        writeJar(new File(input, "boot.jar"), "BOOT-INF/classes/com/acme/App.class");

        CliOptions options = CliOptions.parse(new String[] {
                "--input", input.getAbsolutePath(),
                "--output", output.getAbsolutePath(),
                "--packages", "com.acme"
        });
        SelectiveDecompilerSummary summary = new SelectiveDecompilerSummary();
        SelectiveDecompilerTaskCollector collector = new SelectiveDecompilerTaskCollector(
                options, new PackageMatcher(options.packages), temp.newFolder("tmp").toPath(), summary);

        List<DecompileTask> tasks = collector.collect();

        assertEquals(1, tasks.size());
        assertEquals("com/acme/App.class", tasks.get(0).entryName);
        assertEquals("com.acme.App", tasks.get(0).className);
        assertEquals(options.output.resolve("input").resolve("boot"), tasks.get(0).outputDir);
        assertEquals(new File(input, "boot.jar").toPath().toAbsolutePath().normalize()
                + "!com.acme.App", tasks.get(0).sourceLocation);
    }

    @Test
    public void collectRecordsDirectoryClassSourcePath() throws Exception {
        File input = temp.newFolder("input");
        File output = new File(temp.getRoot(), "out");
        File classFile = new File(input, "com/acme/App.class");
        classFile.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(classFile)) {
            out.write(new byte[] { 0, 1, 2, 3 });
        }

        CliOptions options = CliOptions.parse(new String[] {
                "--input", input.getAbsolutePath(),
                "--output", output.getAbsolutePath(),
                "--packages", "com.acme"
        });
        SelectiveDecompilerSummary summary = new SelectiveDecompilerSummary();
        SelectiveDecompilerTaskCollector collector = new SelectiveDecompilerTaskCollector(
                options, new PackageMatcher(options.packages), temp.newFolder("tmp").toPath(), summary);

        List<DecompileTask> tasks = collector.collect();

        assertEquals(1, tasks.size());
        assertEquals("com.acme.App", tasks.get(0).className);
        assertEquals(options.output.resolve("input"), tasks.get(0).outputDir);
        assertEquals(classFile.toPath().toAbsolutePath().normalize().toString(), tasks.get(0).sourceLocation);
    }

    @Test
    public void separatesDirectoryRootFromJarWithSameOutputName() throws Exception {
        File input = temp.newFolder("input");
        File output = new File(temp.getRoot(), "out");
        File directoryClass = new File(input, "foo/com/acme/Duplicate.class");
        directoryClass.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(directoryClass)) {
            out.write(new byte[] { 0, 1, 2, 3 });
        }
        writeJar(new File(input, "foo.jar"), "com/acme/Duplicate.class");

        CliOptions options = CliOptions.parse(new String[] {
                "--input", input.getAbsolutePath(),
                "--output", output.getAbsolutePath(),
                "--packages", "com.acme"
        });
        SelectiveDecompilerSummary summary = new SelectiveDecompilerSummary();
        SelectiveDecompilerTaskCollector collector = new SelectiveDecompilerTaskCollector(
                options, new PackageMatcher(options.packages), temp.newFolder("tmp").toPath(), summary);

        List<DecompileTask> tasks = collector.collect();

        assertEquals(2, tasks.size());
        assertEquals(0, summary.duplicateUnits.get());
        assertFalse(tasks.get(0).outputDir.equals(tasks.get(1).outputDir));
    }

    @Test
    public void noNestedSkipsNestedArchiveWithoutOpeningIt() throws Exception {
        File input = temp.newFolder("input");
        File output = new File(temp.getRoot(), "out");
        writeJar(new File(input, "outer.jar"), "lib/broken-inner.jar");

        CliOptions options = CliOptions.parse(new String[] {
                "--input", input.getAbsolutePath(),
                "--output", output.getAbsolutePath(),
                "--no-nested"
        });
        SelectiveDecompilerSummary summary = new SelectiveDecompilerSummary();
        SelectiveDecompilerTaskCollector collector = new SelectiveDecompilerTaskCollector(
                options, new PackageMatcher(options.packages), temp.newFolder("tmp").toPath(), summary);

        List<DecompileTask> tasks = collector.collect();

        assertEquals(0, tasks.size());
    }

    @Test
    public void unmatchedNestedArchiveIsNotExtracted() throws Exception {
        File input = temp.newFolder("input");
        File output = new File(temp.getRoot(), "out");
        writeJar(new File(input, "outer.jar"), "lib/dep.jar", nestedJar("com/other/Lib.class"));
        Path tempRoot = temp.newFolder("tmp").toPath();

        List<DecompileTask> tasks = collect(input, output, tempRoot, "com.acme");

        assertEquals(0, tasks.size());
        assertEquals(0, nestedArchiveFiles(tempRoot).size());
    }

    @Test
    public void matchedNestedArchiveIsExtracted() throws Exception {
        File input = temp.newFolder("input");
        File output = new File(temp.getRoot(), "out");
        writeJar(new File(input, "outer.jar"), "lib/dep.jar", nestedJar("com/acme/Lib.class"));
        Path tempRoot = temp.newFolder("tmp").toPath();

        List<DecompileTask> tasks = collect(input, output, tempRoot, "com.acme");

        assertEquals(1, tasks.size());
        assertEquals("com.acme.Lib", tasks.get(0).className);
        assertTrue(tasks.get(0).inputSource instanceof ZipInputSource);
        ZipInputSource source = (ZipInputSource) tasks.get(0).inputSource;
        assertTrue(source.archive.startsWith(tempRoot.toAbsolutePath().normalize().resolve("nested")));
        assertEquals(1, nestedArchiveFiles(tempRoot).size());
    }

    @Test
    public void unmatchedBootInfLibIsNotExtractedWhenAppClassMatches() throws Exception {
        File input = temp.newFolder("input");
        File output = new File(temp.getRoot(), "out");
        writeJar(new File(input, "boot.jar"),
                "BOOT-INF/classes/com/acme/App.class", dummyClass(),
                "BOOT-INF/lib/other.jar", nestedJar("com/other/Lib.class"));
        Path tempRoot = temp.newFolder("tmp").toPath();

        List<DecompileTask> tasks = collect(input, output, tempRoot, "com.acme");

        assertEquals(1, tasks.size());
        assertEquals("com.acme.App", tasks.get(0).className);
        ZipInputSource source = (ZipInputSource) tasks.get(0).inputSource;
        assertEquals(new File(input, "boot.jar").toPath().toAbsolutePath().normalize(), source.archive);
        assertEquals(0, nestedArchiveFiles(tempRoot).size());
    }

    @Test
    public void matchingNestedArchiveStillScansNestedLibraries() throws Exception {
        File input = temp.newFolder("input");
        File output = new File(temp.getRoot(), "out");
        writeJar(new File(input, "outer.jar"), "lib/mid.jar",
                nestedJar("com/acme/App.class", dummyClass(),
                        "lib/dep.jar", nestedJar("com/acme/Lib.class")));
        Path tempRoot = temp.newFolder("tmp").toPath();
        SelectiveDecompilerSummary summary = new SelectiveDecompilerSummary();

        List<DecompileTask> tasks = collect(input, output, tempRoot, "com.acme", summary);

        assertEquals(2, tasks.size());
        assertEquals(0, summary.duplicateUnits.get());
        assertEquals("com.acme.App", tasks.get(1).className);
        assertEquals("com.acme.Lib", tasks.get(0).className);
    }

    @Test
    public void unmatchedNestedCopiesReleaseExtractBudget() throws Exception {
        File input = temp.newFolder("input");
        File output = new File(temp.getRoot(), "out");
        writeJar(new File(input, "outer.jar"), "lib/mid.jar",
                nestedJar("lib/inner.jar", nestedJar("com/other/Lib.class")));
        Path tempRoot = temp.newFolder("tmp").toPath();
        SelectiveDecompilerSummary summary = new SelectiveDecompilerSummary();
        CliOptions options = CliOptions.parse(new String[] {
                "--input", input.getAbsolutePath(),
                "--output", output.getAbsolutePath(),
                "--packages", "com.acme"
        });
        SelectiveDecompilerTaskCollector collector = new SelectiveDecompilerTaskCollector(
                options, new PackageMatcher(options.packages), tempRoot, summary);

        List<DecompileTask> tasks = collector.collect();

        assertEquals(0, tasks.size());
        assertEquals(0, nestedArchiveFiles(tempRoot).size());
        assertEquals(0L, collector.extractedNestedBytes());
    }

    private List<DecompileTask> collect(File input, File output, Path tempRoot, String packages)
            throws Exception {
        return collect(input, output, tempRoot, packages, new SelectiveDecompilerSummary());
    }

    private List<DecompileTask> collect(File input, File output, Path tempRoot, String packages,
                                        SelectiveDecompilerSummary summary)
            throws Exception {
        CliOptions options = CliOptions.parse(new String[] {
                "--input", input.getAbsolutePath(),
                "--output", output.getAbsolutePath(),
                "--packages", packages
        });
        SelectiveDecompilerTaskCollector collector = new SelectiveDecompilerTaskCollector(
                options, new PackageMatcher(options.packages), tempRoot, summary);
        return collector.collect();
    }

    private static List<Path> nestedArchiveFiles(Path tempRoot) throws IOException {
        Path nested = tempRoot.resolve("nested");
        List<Path> files = new ArrayList<Path>();
        if (!Files.isDirectory(nested)) {
            return files;
        }
        DirectoryStream<Path> stream = Files.newDirectoryStream(nested);
        try {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            }
        } finally {
            stream.close();
        }
        return files;
    }

    private static void writeJar(File jarFile, String entryName) throws Exception {
        writeJar(jarFile, entryName, dummyClass());
    }

    private static void writeJar(File jarFile, String entryName, byte[] content) throws Exception {
        writeJar(jarFile, new String[] { entryName }, new byte[][] { content });
    }

    private static void writeJar(File jarFile, String firstName, byte[] firstContent,
                                 String secondName, byte[] secondContent) throws Exception {
        writeJar(jarFile, new String[] { firstName, secondName },
                new byte[][] { firstContent, secondContent });
    }

    private static void writeJar(File jarFile, String[] names, byte[][] contents) throws Exception {
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jarFile));
        try {
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i]));
                zip.write(contents[i]);
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
    }

    private static byte[] dummyClass() {
        return new byte[] { 0, 1, 2, 3 };
    }

    private static byte[] nestedJar(String entryName) throws Exception {
        return nestedJar(new String[] { entryName }, new byte[][] { dummyClass() });
    }

    private static byte[] nestedJar(String firstName, byte[] firstContent) throws Exception {
        return nestedJar(new String[] { firstName }, new byte[][] { firstContent });
    }

    private static byte[] nestedJar(String firstName, byte[] firstContent,
                                    String secondName, byte[] secondContent) throws Exception {
        return nestedJar(new String[] { firstName, secondName },
                new byte[][] { firstContent, secondContent });
    }

    private static byte[] nestedJar(String[] names, byte[][] contents) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i]));
                zip.write(contents[i]);
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
        return out.toByteArray();
    }
}

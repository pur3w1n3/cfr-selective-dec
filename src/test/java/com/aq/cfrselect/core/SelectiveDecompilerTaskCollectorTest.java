package com.aq.cfrselect.core;

import com.aq.cfrselect.cli.CliOptions;
import com.aq.cfrselect.matching.PackageMatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;

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
                "--packages", "com.acme"
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

    private static void writeJar(File jarFile, String entryName) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jarFile))) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(new byte[] { 0, 1, 2, 3 });
            zip.closeEntry();
        }
    }
}

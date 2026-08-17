package com.aq.cfrselect.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SourceWorkspaceTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void missingEntryOnlyFailsItsOwnSourceUnit() throws Exception {
        File archive = new File(temp.getRoot(), "classes.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("com/acme/Good.class"));
            zip.write(new byte[] { 0, 1, 2, 3 });
            zip.closeEntry();
        }
        Path output = temp.newFolder("output").toPath();
        DecompileTask good = new DecompileTask("good", output, "com/acme/Good.class", "good",
                new ZipInputSource(archive.toPath(), "com/acme/Good.class", -1L, -1L, "test"), null);
        DecompileTask missing = new DecompileTask("missing", output, "com/acme/Missing.class", "missing",
                new ZipInputSource(archive.toPath(), "com/acme/Missing.class", -1L, -1L, "test"), null);
        List<DecompileUnit> units = DecompileUnit.group(Arrays.asList(good, missing));
        Path workspaceRoot = temp.newFolder("workspace").toPath();
        SourceWorkspace workspace = new SourceWorkspace(workspaceRoot, units);

        workspace.prepare();

        assertNull(workspace.preparationFailure(units.get(0)));
        assertNotNull(workspace.preparationFailure(units.get(1)));
        assertTrue(java.nio.file.Files.isRegularFile(
                workspaceRoot.resolve("com/acme/Good.class")));
    }
}

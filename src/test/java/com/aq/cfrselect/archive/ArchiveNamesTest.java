package com.aq.cfrselect.archive;

import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArchiveNamesTest {
    @Test
    public void mapJarClassEntryStripsKnownApplicationRoots() {
        assertEquals("com/acme/App.class",
                ArchiveNames.mapJarClassEntry("BOOT-INF/classes/com/acme/App.class"));
        assertEquals("com/acme/App.class",
                ArchiveNames.mapJarClassEntry("WEB-INF/classes/com/acme/App.class"));
        assertEquals("com/acme/App.class",
                ArchiveNames.mapJarClassEntry("com/acme/App.class"));
    }

    @Test
    public void safeJarEntryNameRejectsTraversalAndAbsolutePaths() {
        assertTrue(ArchiveNames.isSafeJarEntryName("com/acme/App.class"));
        assertFalse(ArchiveNames.isSafeJarEntryName("../App.class"));
        assertFalse(ArchiveNames.isSafeJarEntryName("/com/acme/App.class"));
        assertFalse(ArchiveNames.isSafeJarEntryName("C:/tmp/App.class"));
        assertFalse(ArchiveNames.isSafeJarEntryName("com//App.class"));
    }

    @Test
    public void supportedTopLevelArchivesAreJarAndWarOnly() {
        assertTrue(ArchiveNames.isSupportedTopLevelArchive(Paths.get("app.jar")));
        assertTrue(ArchiveNames.isSupportedTopLevelArchive(Paths.get("app.war")));
        assertFalse(ArchiveNames.isSupportedTopLevelArchive(Paths.get("app.zip")));
    }
}

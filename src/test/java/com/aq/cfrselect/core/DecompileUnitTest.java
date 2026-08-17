package com.aq.cfrselect.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class DecompileUnitTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void groupsInnerClassesIntoOuterSourceUnitAndPrefersOuterInput() throws Exception {
        Path classRoot = temp.newFolder("classes").toPath();
        Path output = temp.newFolder("output").toPath();
        DecompileTask inner = task(classRoot, output, "com/acme/Outer$Inner.class",
                "com/acme/Outer.class");
        DecompileTask anonymous = task(classRoot, output, "com/acme/Outer$1.class",
                "com/acme/Outer.class");
        DecompileTask outer = task(classRoot, output, "com/acme/Outer.class");

        List<DecompileUnit> units = DecompileUnit.group(Arrays.asList(inner, anonymous, outer));

        assertEquals(1, units.size());
        assertEquals("com/acme/Outer.class", units.get(0).primary.entryName);
        assertEquals("com/acme/Outer.java", units.get(0).sourceEntry);
        assertEquals(3, units.get(0).classCount());
    }

    @Test
    public void keepsDifferentTopLevelClassesAsSeparateUnits() throws Exception {
        Path classRoot = temp.newFolder("classes").toPath();
        Path output = temp.newFolder("output").toPath();

        List<DecompileUnit> units = DecompileUnit.group(Arrays.asList(
                task(classRoot, output, "com/acme/First.class"),
                task(classRoot, output, "com/acme/Second.class")));

        assertEquals(2, units.size());
    }

    @Test
    public void balancesGroupsWithoutIncreasingGroupCount() {
        assertEquals(128, SelectiveDecompilerExecutor.balancedBatchSize(128, 128));
        assertEquals(65, SelectiveDecompilerExecutor.balancedBatchSize(129, 128));
        assertEquals(86, SelectiveDecompilerExecutor.balancedBatchSize(257, 128));
        assertEquals(122, SelectiveDecompilerExecutor.balancedBatchSize(732, 128));
        assertEquals(1, SelectiveDecompilerExecutor.balancedBatchSize(7, 1));
    }

    @Test
    public void doesNotMergeTopLevelClassWhoseNameContainsDollar() throws Exception {
        Path classRoot = temp.newFolder("classes").toPath();
        Path output = temp.newFolder("output").toPath();

        List<DecompileUnit> units = DecompileUnit.group(Arrays.asList(
                task(classRoot, output, "com/acme/Foo.class"),
                task(classRoot, output, "com/acme/Foo$Bar.class")));

        assertEquals(2, units.size());
        assertEquals("com/acme/Foo.java", units.get(0).sourceEntry);
        assertEquals("com/acme/Foo$Bar.java", units.get(1).sourceEntry);
    }

    private DecompileTask task(Path classRoot, Path output, String entryName) {
        return task(classRoot, output, entryName, null);
    }

    private DecompileTask task(Path classRoot, Path output, String entryName, String outerEntry) {
        Path classFile = classRoot.resolve(entryName);
        return new DecompileTask(entryName, output, entryName, classFile.toString(),
                new DirectoryInputSource(classFile, entryName, null), outerEntry);
    }
}

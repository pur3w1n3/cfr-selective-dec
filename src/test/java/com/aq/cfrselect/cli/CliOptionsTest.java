package com.aq.cfrselect.cli;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class CliOptionsTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void namedArgumentsParsePackageList() throws Exception {
        File input = temp.newFolder("input");
        File output = new File(temp.getRoot(), "out");

        CliOptions options = CliOptions.parse(new String[] {
                "--input", input.getAbsolutePath(),
                "--output", output.getAbsolutePath(),
                "--packages", "com.foo,org.demo"
        });

        assertEquals(input.toPath().toAbsolutePath().normalize(), options.input);
        assertEquals(output.toPath().toAbsolutePath().normalize(), options.output);
        assertEquals(2, options.packages.size());
        assertEquals("com.foo", options.packages.get(0));
        assertEquals("org.demo", options.packages.get(1));
        assertFalse(options.help);
    }

    @Test(expected = UsageException.class)
    public void positionalArgumentsRejectUnknownOptions() throws Exception {
        File input = temp.newFolder("input");
        File output = new File(temp.getRoot(), "out");

        CliOptions.parse(new String[] {
                input.getAbsolutePath(),
                output.getAbsolutePath(),
                "--unknown"
        });
    }
}

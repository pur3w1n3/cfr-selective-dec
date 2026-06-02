package com.aq.cfrselect.matching;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PackageMatcherTest {
    @Test
    public void emptyPackageListMatchesAllClassEntries() {
        PackageMatcher matcher = new PackageMatcher(Collections.<String>emptyList());

        assertTrue(matcher.matchesClassEntry("com/acme/App.class"));
        assertTrue(matcher.matchesClassEntry("org/demo/Other.class"));
        assertFalse(matcher.matchesClassEntry("com/acme/App.txt"));
    }

    @Test
    public void packagePrefixesSupportDotsSlashesAndWildcards() {
        PackageMatcher matcher = new PackageMatcher(Arrays.asList("com.acme.*", "org/demo"));

        assertTrue(matcher.matchesClassEntry("com/acme/App.class"));
        assertTrue(matcher.matchesClassEntry("org/demo/Other.class"));
        assertFalse(matcher.matchesClassEntry("com/other/App.class"));
    }
}

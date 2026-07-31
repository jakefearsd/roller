/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller.weblogger.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the classloader that lets scheduled tasks (TaskRunner) run Roller
 * classes from outside a servlet container.
 *
 * <p>It has to assemble the same classpath the container would: every jar in
 * {@code WEB-INF/lib}, the exploded {@code WEB-INF/classes} directory, and any
 * extra jars directory (where the JDBC driver lives). Missing one of those
 * shows up only as a NoClassDefFoundError in a cron job, so the assembled URL
 * list is worth asserting directly.
 */
public class StandaloneWebappClassLoaderTest {

    private Path webapp;
    private Path jars;

    private void layOutWebapp(Path root) throws IOException {
        webapp = root.resolve("webapp");
        Path lib = webapp.resolve("WEB-INF/lib");
        Files.createDirectories(lib);
        Files.createDirectories(webapp.resolve("WEB-INF/classes"));
        Files.createFile(lib.resolve("roller.jar"));
        Files.createFile(lib.resolve("commons-lang.jar"));
        Files.createFile(lib.resolve("notes.txt"));

        jars = root.resolve("extra");
        Files.createDirectories(jars);
        Files.createFile(jars.resolve("postgresql.jar"));
    }

    @Test
    public void includesEveryJarTheClassesDirectoryAndTheExtraJars(@TempDir Path root) throws Exception {
        layOutWebapp(root);

        try (StandaloneWebappClassLoader loader =
                     new StandaloneWebappClassLoader(webapp.toString(), jars.toString(), null)) {
            List<String> urls = Arrays.stream(loader.getURLs()).map(URL::toString).toList();

            assertTrue(urls.stream().anyMatch(u -> u.endsWith("/WEB-INF/lib/roller.jar")), urls.toString());
            assertTrue(urls.stream().anyMatch(u -> u.endsWith("/WEB-INF/lib/commons-lang.jar")), urls.toString());
            assertTrue(urls.stream().anyMatch(u -> u.endsWith("/WEB-INF/classes/")),
                    "WEB-INF/classes must be on the classpath with a trailing slash, or the "
                            + "JVM treats it as a jar file and finds nothing in it: " + urls);
            assertTrue(urls.stream().anyMatch(u -> u.endsWith("/postgresql.jar")),
                    "The extra jars directory holds the JDBC driver; without it a scheduled "
                            + "task cannot reach the database: " + urls);
        }
    }

    @Test
    public void ignoresFilesInLibThatAreNotJars(@TempDir Path root) throws Exception {
        layOutWebapp(root);

        try (StandaloneWebappClassLoader loader =
                     new StandaloneWebappClassLoader(webapp.toString(), jars.toString(), null)) {
            assertEquals(4, loader.getURLs().length,
                    "Expected two lib jars, WEB-INF/classes and one extra jar. A README or "
                            + "licence file in WEB-INF/lib must not be added as a code source: "
                            + Arrays.toString(loader.getURLs()));
        }
    }

    @Test
    public void refusesToStartWhenTheLibDirectoryIsMissing(@TempDir Path root) throws IOException {
        // Sharp edge worth pinning: a mistyped webapp path fails immediately
        // with an exception rather than silently building a classpath with no
        // jars on it, which would fail much later and far less clearly.
        layOutWebapp(root);
        Path wrong = root.resolve("no-such-webapp");

        assertThrows(NullPointerException.class,
                () -> new StandaloneWebappClassLoader(wrong.toString(), jars.toString(), null));
    }

    @Test
    public void theTwoArgumentConstructorUsesTheCallersParentLoader(@TempDir Path root) throws Exception {
        layOutWebapp(root);

        try (StandaloneWebappClassLoader loader =
                     new StandaloneWebappClassLoader(webapp.toString(), jars.toString())) {
            assertEquals(4, loader.getURLs().length);
            // Delegation to a parent is what lets the task see the JDK and the
            // caller's own classes; with an explicit null parent it would not.
            assertTrue(loader.loadClass("java.lang.String") == String.class);
        }
    }
}

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
package org.apache.roller.weblogger.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that every /webjars/ URL hardcoded in a JSP, tag file or Velocity
 * template actually resolves to a resource inside a webjar on the classpath.
 *
 * Webjar versions appear in two places that drift independently: the
 * dependency versions in app/pom.xml, and the URLs written by hand into
 * templates such as WEB-INF/jsps/tiles/head.jsp. When a dependency is bumped
 * and the template is not, the asset silently 404s at runtime. Because
 * head.jsp is included by every admin page, such a mismatch takes out the
 * editor, date pickers and client-side validation site-wide -- with no
 * compile error and no other failing test.
 */
public class WebjarReferenceTest {

    /**
     * Captures everything after "/webjars/" up to the closing quote, e.g.
     * "jquery-ui/1.14.2/jquery-ui.min.js". Trailing path segments may contain
     * slashes (summernote serves from a dist/ subdirectory).
     */
    private static final Pattern WEBJAR_URL = Pattern.compile("/webjars/([^'\"\\s>]+)");

    private static final Path WEBAPP = Paths.get("src/main/webapp");

    @Test
    public void everyWebjarReferenceResolvesOnTheClasspath() throws IOException {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (Path template : templateFiles()) {
            String content = Files.readString(template, StandardCharsets.UTF_8);
            Matcher matcher = WEBJAR_URL.matcher(content);
            while (matcher.find()) {
                String reference = matcher.group(1);
                checked++;
                String resource = "META-INF/resources/webjars/" + reference;
                if (getClass().getClassLoader().getResource(resource) == null) {
                    failures.add(WEBAPP.relativize(template) + " -> /webjars/" + reference);
                }
            }
        }

        assertTrue(checked > 0,
                "Found no /webjars/ references to check under " + WEBAPP.toAbsolutePath()
                        + " -- the test is not looking where it thinks it is.");

        assertTrue(failures.isEmpty(),
                "Webjar URL(s) do not match any webjar on the classpath. The dependency "
                        + "version in app/pom.xml was most likely bumped without updating the "
                        + "template, which 404s the asset at runtime:\n  "
                        + String.join("\n  ", failures));
    }

    /**
     * Guards the guard: if head.jsp stops being scanned (renamed, moved, or the
     * scan silently narrows), the test above would vacuously pass.
     */
    @Test
    public void headJspIsScannedAndReferencesWebjars() throws IOException {
        Path headJsp = WEBAPP.resolve("WEB-INF/jsps/tiles/head.jsp");
        assertTrue(Files.exists(headJsp), "Expected to find " + headJsp.toAbsolutePath());
        assertTrue(templateFiles().contains(headJsp),
                headJsp + " is not picked up by the template scan");
        assertFalse(templateFiles().isEmpty(), "Template scan returned nothing");

        String content = Files.readString(headJsp, StandardCharsets.UTF_8);
        assertTrue(WEBJAR_URL.matcher(content).find(),
                headJsp + " no longer references any webjar; if the assets moved, "
                        + "update this test to follow them.");
    }

    private List<Path> templateFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(WEBAPP)) {
            return paths.filter(Files::isRegularFile)
                    .filter(WebjarReferenceTest::isTemplate)
                    .toList();
        }
    }

    private static boolean isTemplate(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".jsp") || name.endsWith(".tag") || name.endsWith(".vm");
    }
}

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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every message code a JSP asks for exists in the default resource
 * bundle.
 *
 * <p>Spring's {@code <spring:message>} throws {@code JspTagException} when a code
 * is missing, which becomes an HTTP 500 for the whole page. Nothing catches that
 * at build time: the JSP compiles, the code is just a string literal, and the
 * page only dies when somebody opens it.
 *
 * <p>That is not hypothetical. {@code UserEdit.jsp} asked for
 * {@code userAdmin.tip.username} and {@code userAdmin.tip.userEnabled} while the
 * bundle defined {@code userAdmin.tip.userName} and {@code userAdmin.tip.enabled}
 * -- a capital N and a renamed key. The admin "Create User" page returned 500 for
 * everyone until a browser integration test happened to open it.
 *
 * <p>Only the default bundle is checked. Translations are allowed to lag; a
 * missing translation falls back to the default, but a missing default is a 500.
 */
public class MessageKeyTest {

    private static final Path JSP_ROOT = Paths.get("src/main/webapp/WEB-INF");

    private static final String DEFAULT_BUNDLE = "/ApplicationResources.properties";

    /**
     * Matches {@code code='some.key'} / {@code code="some.key"} on spring:message
     * and fmt:message tags. Only literal codes are checked -- a code assembled
     * from EL cannot be resolved statically, and those are rare enough to accept.
     */
    private static final Pattern MESSAGE_CODE = Pattern.compile(
            "<(?:spring|fmt):message\\b[^>]*\\bcode\\s*=\\s*[\"']([A-Za-z0-9_.]+)[\"']");

    @Test
    public void everyMessageCodeUsedInAJspExists() throws IOException {
        Properties bundle = loadDefaultBundle();
        Set<String> missing = new TreeSet<>();
        List<String> where = new ArrayList<>();
        int checked = 0;

        for (Path jsp : templateFiles()) {
            String content = Files.readString(jsp, StandardCharsets.UTF_8);
            Matcher matcher = MESSAGE_CODE.matcher(content);
            while (matcher.find()) {
                String code = matcher.group(1);
                checked++;
                if (!bundle.containsKey(code)) {
                    missing.add(code);
                    where.add(code + "  (" + JSP_ROOT.relativize(jsp) + ")");
                }
            }
        }

        assertTrue(checked > 0,
                "Found no <spring:message> codes under " + JSP_ROOT.toAbsolutePath()
                        + " -- the scan is not looking where it thinks it is.");

        assertTrue(missing.isEmpty(),
                "JSPs reference message codes that ApplicationResources.properties does not "
                        + "define. spring:message throws JspTagException for a missing code, so "
                        + "each of these renders as HTTP 500:\n  "
                        + String.join("\n  ", where));
    }

    /**
     * Guards the guard: if the scan stopped finding UserEdit.jsp, the check above
     * would pass while proving nothing.
     */
    @Test
    public void theScanCoversTheAdminPages() throws IOException {
        Path userEdit = JSP_ROOT.resolve("jsps/admin/UserEdit.jsp");
        assertTrue(Files.exists(userEdit), "Expected " + userEdit.toAbsolutePath());
        assertTrue(templateFiles().contains(userEdit),
                userEdit + " is not picked up by the template scan");

        String content = Files.readString(userEdit, StandardCharsets.UTF_8);
        assertTrue(MESSAGE_CODE.matcher(content).find(),
                userEdit + " no longer uses spring:message; update this test to follow it.");
    }

    /**
     * Bundle keys nothing refers to are dead weight in eight translated files.
     *
     * <p>Ratchet: after the Stage 1D i18n sweep the true orphan count is 0. The
     * bundle still carries {@link #KNOWN_DYNAMIC_KEY_COUNT} keys this text scan
     * cannot see because they are addressed dynamically rather than via a
     * literal {@code <spring:message code="...">}:
     * <ul>
     *   <li>38 {@code configForm.*} keys read off {@code key="..."} attributes in
     *       {@code runtimeConfigDefs.xml} (GlobalConfig.jsp renders display
     *       groups/properties generically via {@code ${dg.key}} / property
     *       metadata, not a literal code; seven more than the Stage 1E baseline
     *       of 31: the Stage 2 Wave 1 {@code uploads.exif.stripGps}, the Wave 4
     *       {@code entry.revisions.retention}, and the five properties promoted
     *       from startup-only to runtime-settable so one running instance can be
     *       reconfigured across them -- {@code groupblogging.enabled},
     *       {@code user.hideUserNames}, {@code comment.throttle.enabled},
     *       {@code share.password.throttle.enabled} and
     *       {@code weblogentry.title.useUnderscoreSeparator}).</li>
     *   <li>18 {@code tabbedmenu.*} keys read off {@code name="..."} attributes in
     *       {@code admin-menu.xml} / {@code editor-menu.xml}: {@code MenuHelper}
     *       copies the XML {@code name} into {@code MenuTab}/{@code MenuTabItem}
     *       {@code key}, which the JSPs render as {@code <spring:message
     *       code="${tab.key}">} -- see {@code tiles/bannerStatus.jsp} and
     *       {@code admin/GlobalConfig.jsp}. Two more than the Stage 1E baseline
     *       of 16: the Stage 2 Wave A {@code tabbedmenu.pages} menu item for the
     *       static page editor, and the Wave B {@code tabbedmenu.submissions}
     *       menu item for the contact-inquiries inbox.</li>
     * </ul>
     * If this count grows beyond that known set, either a new key just went
     * dynamic-only (extend the exclusion and document it here) or a genuine
     * orphan was added (delete it instead of raising the ratchet).
     */
    @Test
    public void reportsBundleKeysNoJspOrControllerUses() throws IOException {
        Properties bundle = loadDefaultBundle();
        String allSources = readAllSources();

        List<String> unused = bundle.stringPropertyNames().stream()
                .filter(key -> !allSources.contains(key))
                .sorted()
                .toList();

        assertFalse(bundle.isEmpty(), "Default bundle is empty");
        assertTrue(unused.size() <= KNOWN_DYNAMIC_KEY_COUNT,
                "Message keys with no textual reference grew to " + unused.size()
                        + " (ratchet is " + KNOWN_DYNAMIC_KEY_COUNT + " of " + bundle.size()
                        + "). New orphans should be deleted from the bundle rather than "
                        + "raising this number; keys addressed dynamically (runtimeConfigDefs.xml, "
                        + "admin/editor-menu.xml) should be added to the documented exclusion set "
                        + "instead:\n  " + String.join("\n  ", unused));
    }

    /**
     * Keys the text scan cannot see: {@code runtimeConfigDefs.xml} {@code key=}
     * attributes (38) + {@code admin-menu.xml}/{@code editor-menu.xml}
     * {@code name=} attributes (18). See the javadoc on
     * {@link #reportsBundleKeysNoJspOrControllerUses()}.
     */
    private static final int KNOWN_DYNAMIC_KEY_COUNT = 56;

    private Properties loadDefaultBundle() throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream(DEFAULT_BUNDLE)) {
            assertTrue(in != null, DEFAULT_BUNDLE + " not found on the classpath");
            props.load(in);
        }
        return props;
    }

    private String readAllSources() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path root : List.of(Paths.get("src/main/webapp"), Paths.get("src/main/java"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path p : paths.filter(Files::isRegularFile).toList()) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".jsp") || name.endsWith(".tag")
                            || name.endsWith(".vm") || name.endsWith(".java")) {
                        all.append(Files.readString(p, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return all.toString();
    }

    private List<Path> templateFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(JSP_ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".jsp") || n.endsWith(".tag");
                    })
                    .toList();
        }
    }
}

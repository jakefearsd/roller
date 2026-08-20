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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every call site must pass exactly as many arguments as its message declares
 * {@code &#123;n&#125;} placeholders.
 *
 * <p>Both directions are silent defects in production, and neither shows up as
 * an exception:
 *
 * <ul>
 *   <li><b>Too few.</b> {@code alwaysUseMessageFormat} is {@code false} on the
 *       {@code ResourceBundleMessageSource} (see {@code WebMvcConfig}), so a
 *       no-argument lookup never runs the value through {@code MessageFormat}
 *       at all -- the raw pattern reaches the page and a reader sees a literal
 *       {@code &#123;0&#125;}. With arguments but too few, {@code MessageFormat}
 *       leaves the surplus placeholder in place, same visible result.</li>
 *   <li><b>Too many.</b> The extra argument is dropped without complaint, so
 *       the value the code went to the trouble of computing -- a category name,
 *       a username -- never reaches the reader. That is how {@code
 *       categoryForm.created} came to read "Category  created", with the double
 *       space where the name used to be.</li>
 * </ul>
 *
 * <p>Scanning is {@link MessageUsageScanner}'s job, shared with {@link
 * MessageKeyTest} so the two ratchets agree on what a call site is. Keys built
 * dynamically are invisible to it by construction and are simply not checked;
 * the small set of call sites whose argument list cannot be counted statically
 * is pinned by {@link #theUncountableCallSitesAreTheKnownTwo()} rather than
 * left to grow.
 */
public class MessagePlaceholderContractTest {

    private static final String DEFAULT_BUNDLE = "/ApplicationResources.properties";

    /**
     * {@code &#123;0&#125;} and also {@code &#123;0,date,...&#125;} -- the format
     * type is part of the placeholder, so a pattern matching only the bare form
     * would read {@code weblogEntryQuery.date.toStringFormat} as taking no
     * arguments at all.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\s*(\\d+)\\s*[,}]");

    /**
     * The one message deliberately allowed to declare a placeholder that no
     * server-side call site fills: {@code EntryEdit.jsp} / {@code PageEdit.jsp}
     * hand the raw pattern to the browser in a {@code data-template} attribute
     * and {@code roller-draft.js} substitutes {@code &#123;0&#125;} itself with
     * the draft's local timestamp, which only the browser knows.
     */
    private static final Set<String> CLIENT_SIDE_SUBSTITUTION = Set.of(
            "weblogEdit.draftRecovery.message");

    /**
     * Today's known placeholder-contract violations, verbatim -- emptied by Task 8.
     *
     * <p>The assertion below is equality, not containment, so this set fails the
     * build in both directions: a newly-introduced mismatch is not absorbed, and
     * a silently-repaired one has to be removed from here in the same commit
     * that repairs it.
     *
     * <p>Deliberately absent: {@code memberPermissions.membersChanged}, which
     * <em>is</em> a defect ({@code MembersController} passes a hardcoded {@code
     * "1"} into "Changed permission for &#123;0&#125; user(s)") but not one this
     * ratchet can see. One placeholder, one argument -- the contract holds; what
     * is wrong is the value of the argument, which no static check reaches. Fix
     * it, but do not expect this test to have flagged it.
     */
    private static final Set<String> EXPECTED_LEGACY_OFFENDERS = Set.of(
            // Too few: the value's second placeholder is never filled, so the
            // admin sees a literal {1} where the rejected value should be.
            "ConfigForm.invalidBooleanProperty",
            "ConfigForm.invalidIntegerProperty",
            "ConfigForm.invalidFloatProperty",
            // Too few: MediaFileBase passes nothing, so the filename never
            // appears and a literal {0} does.
            "mediaFile.delete.error",
            // Too many: the value computed at the call site is silently dropped.
            // categoryForm.created still carries the double space the removed
            // {0} left behind.
            "categoryForm.created",
            "categoryForm.error.duplicateName",
            "createUser.add.success",
            "pageForm.save.success",
            "stylesheetEdit.save.success",
            "stylesheetEdit.revert.success",
            "stylesheetEdit.default.success");

    /**
     * Call sites whose argument count cannot be read off the source: both pass a
     * local {@code String[] args} whose length is not visible at the call.
     * (Both happen to be correct -- two-element arrays against two-placeholder
     * messages -- but the scanner cannot prove it, so it declines to guess.)
     * Pinned so the silently-skipped set cannot grow into a hole in the ratchet.
     */
    private static final Set<String> UNCOUNTABLE_CALL_SITES = Set.of(
            "business/FileContentManagerImpl.java -> error.upload.filemax",
            "business/FileContentManagerImpl.java -> error.upload.forbiddenFile");

    @Test
    public void everyCallSitePassesTheArgumentsItsMessageDeclares() throws IOException {
        Properties bundle = loadDefaultBundle();
        assertFalse(bundle.isEmpty(), "Default bundle is empty");

        Map<String, List<String>> violations = new TreeMap<>();
        int checked = 0;

        for (MessageUsageScanner.Usage usage : MessageUsageScanner.scanAll()) {
            String value = bundle.getProperty(usage.key());
            if (value == null || usage.argCount() == MessageUsageScanner.UNRESOLVED
                    || CLIENT_SIDE_SUBSTITUTION.contains(usage.key())) {
                continue;
            }
            checked++;
            int declared = highestPlaceholderIndex(value) + 1;
            if (usage.argCount() != declared) {
                violations.computeIfAbsent(usage.key(), k -> new ArrayList<>())
                        .add(usage.where() + " passes " + usage.argCount()
                                + ", value declares " + declared + ": \"" + value + "\"");
            }
        }

        assertTrue(checked > 500,
                "Only " + checked + " call sites were checked -- the scan is not looking "
                        + "where it thinks it is.");

        assertEquals(EXPECTED_LEGACY_OFFENDERS, violations.keySet(),
                "Message keys whose call sites disagree with their placeholders. Too few "
                        + "arguments prints a literal {n} on the page; too many silently drops "
                        + "the value. Neither throws:\n" + render(violations));
    }

    @Test
    public void theUncountableCallSitesAreTheKnownTwo() throws IOException {
        Set<String> uncountable = new TreeSet<>();
        for (MessageUsageScanner.Usage usage : MessageUsageScanner.scanAll()) {
            if (usage.argCount() == MessageUsageScanner.UNRESOLVED) {
                uncountable.add(shortSite(usage));
            }
        }
        assertEquals(UNCOUNTABLE_CALL_SITES, uncountable,
                "The set of call sites this scanner silently skips has changed. Every entry "
                        + "here is a hole in everyCallSitePassesTheArgumentsItsMessageDeclares(), "
                        + "so it may only grow with a stated reason.");
    }

    /**
     * Guards the allowlist: if {@code weblogEdit.draftRecovery.message} ever
     * stopped declaring a placeholder, the exemption above would be silently
     * excusing nothing and should be deleted rather than left behind.
     */
    @Test
    public void theClientSideSubstitutionAllowlistStillHasSomethingToExcuse() throws IOException {
        Properties bundle = loadDefaultBundle();
        for (String key : CLIENT_SIDE_SUBSTITUTION) {
            String value = bundle.getProperty(key);
            assertTrue(value != null && highestPlaceholderIndex(value) >= 0,
                    key + " is allowlisted for client-side {0} substitution but no longer "
                            + "declares a placeholder -- drop it from the allowlist.");
        }
    }

    private static String shortSite(MessageUsageScanner.Usage usage) {
        String path = usage.where().substring(0, usage.where().lastIndexOf(':'));
        int pkg = path.indexOf("org/apache/roller/weblogger/");
        if (pkg >= 0) {
            path = path.substring(pkg + "org/apache/roller/weblogger/".length());
        }
        return path.replace('\\', '/') + " -> " + usage.key();
    }

    private static int highestPlaceholderIndex(String value) {
        int highest = -1;
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        }
        return highest;
    }

    private static String render(Map<String, List<String>> violations) {
        StringBuilder out = new StringBuilder();
        violations.forEach((key, sites) -> {
            out.append("  ").append(key).append('\n');
            sites.forEach(site -> out.append("      ").append(site).append('\n'));
        });
        return out.toString();
    }

    private Properties loadDefaultBundle() throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream(DEFAULT_BUNDLE)) {
            assertTrue(in != null, DEFAULT_BUNDLE + " not found on the classpath");
            props.load(in);
        }
        return props;
    }
}

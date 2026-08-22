/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.roller.weblogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the end state of the 2026-08-22 "retire the static service locator"
 * wave, and is its migration ledger while the wave is in flight.
 *
 * <p>Source scan, same family as {@code QualityGatePomTest} and
 * {@code ControllerMetadataTest}: nothing here needs a container. Three
 * rules, each over every {@code .java} under {@code app/src/main/java} with
 * comments and javadoc stripped first (a mention in prose is not a call
 * site):
 *
 * <ol>
 *   <li>The set of files referencing {@code WebloggerFactory} equals
 *       {@link #ALLOWED}, in both directions. A new call site in a file not
 *       on the list fails immediately -- even mid-wave -- which is why this
 *       is an explicit file list and not a count. A file still on the list
 *       after its references are gone fails too: the list is the ledger, and
 *       a ledger that is not kept is not one.</li>
 *   <li>No main-source {@code static} field has a business-tier type
 *       ({@link #BUSINESS_TIER_TYPES}), except the two named residuals in
 *       {@link #STATIC_RESIDUALS}. This is the rule that stops the locator
 *       coming back under another name.</li>
 *   <li>No JPA entity or domain pojo under {@code pojos/} references a
 *       business-tier type. Entities are data plus invariants; behaviour
 *       that needs a collaborator belongs in a service (spec Decision 5).
 *       {@code pojos/wrapper/} is deliberately <em>outside</em> this rule:
 *       the wrappers are the Velocity presentation adapter and hold a
 *       {@code URLStrategy} today, and the facade after Task 13, by
 *       design.</li>
 * </ol>
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-08-22-retire-static-service-locator-design.md},
 * Decisions 2, 5, 7 and 8.
 */
class StaticServiceLocatorTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path MAIN = REPO.resolve("app/src/main/java");
    private static final String PKG = "app/src/main/java/org/apache/roller/weblogger/";

    /** The static shim itself; deleted by the plan's Task 20, excluded from every rule until then. */
    private static final String SHIM = PKG + "business/WebloggerFactory.java";

    // ---------------------------------------------------------------------
    // The wave's migration ledger. These three sets ONLY EVER SHRINK. Each
    // task in docs/superpowers/plans/2026-08-22-retire-static-service-locator.md
    // ends by removing its files here; the wave is done when ALLOWED and
    // POJO_ALLOWED are empty and the shim no longer exists.
    // ---------------------------------------------------------------------

    /**
     * Main-source files that still reach the business tier through
     * {@code WebloggerFactory}. Seeded from the tree at {@code 23d0e8042}:
     * 73 files, 164 call sites.
     */
    static final Set<String> ALLOWED = Set.of(
            PKG + "boot/RollerLifecycle.java",
            PKG + "boot/SecurityConfig.java",
            PKG + "business/VirtualHostRegistry.java",
            PKG + "business/jpa/JPAMediaFileManagerImpl.java",
            PKG + "business/jpa/JPAWeblogManagerImpl.java",
            PKG + "business/runnable/RollerTaskWithLeasing.java",
            PKG + "business/runnable/ScheduledEntriesTask.java",
            PKG + "business/runnable/TaskScheduler.java",
            PKG + "business/runnable/TrashPurgeTask.java",
            PKG + "business/search/lucene/LuceneIndexManager.java",
            PKG + "business/shortcodes/GalleryShortcode.java",
            PKG + "business/shortcodes/ImageShortcode.java",
            PKG + "business/shortcodes/MapShortcode.java",
            PKG + "business/themes/ThemeManagerImpl.java",
            PKG + "business/themes/WeblogCustomTheme.java",
            PKG + "business/themes/WeblogSharedTheme.java",
            PKG + "config/WebloggerRuntimeConfig.java",
            PKG + "pojos/GlobalPermission.java",
            PKG + "pojos/MediaFile.java",
            PKG + "pojos/User.java",
            PKG + "pojos/Weblog.java",
            PKG + "pojos/WeblogCategory.java",
            PKG + "pojos/WeblogEntry.java",
            PKG + "pojos/WeblogEntryTag.java",
            PKG + "pojos/WeblogPermission.java",
            PKG + "pojos/wrapper/WeblogEntryWrapper.java",
            PKG + "ui/controllers/RollerHandlerInterceptor.java",
            PKG + "ui/controllers/admin/CreateUserBean.java",
            PKG + "ui/controllers/ajax/ThemeDataServlet.java",
            PKG + "ui/controllers/ajax/UserDataServlet.java",
            PKG + "ui/controllers/core/InstallController.java",
            PKG + "ui/controllers/editor/EntryBean.java",
            PKG + "ui/controllers/editor/TemplateEditBean.java",
            PKG + "ui/core/RollerSession.java",
            PKG + "ui/core/filters/BootstrapFilter.java",
            PKG + "ui/core/filters/PersistenceSessionFilter.java",
            PKG + "ui/core/security/RollerUserDetailsService.java",
            PKG + "ui/core/util/menu/MenuHelper.java",
            PKG + "ui/rendering/WeblogRequestMapper.java",
            PKG + "ui/rendering/model/ConfigModel.java",
            PKG + "ui/rendering/model/FeedModel.java",
            PKG + "ui/rendering/model/MenuModel.java",
            PKG + "ui/rendering/model/PageModel.java",
            PKG + "ui/rendering/model/PreviewPageModel.java",
            PKG + "ui/rendering/model/PreviewURLModel.java",
            PKG + "ui/rendering/model/SearchResultsModel.java",
            PKG + "ui/rendering/model/SiteModel.java",
            PKG + "ui/rendering/model/URLModel.java",
            PKG + "ui/rendering/pagers/AbstractWeblogEntriesPager.java",
            PKG + "ui/rendering/pagers/UsersPager.java",
            PKG + "ui/rendering/pagers/WeblogEntriesListPager.java",
            PKG + "ui/rendering/pagers/WeblogEntriesPermalinkPager.java",
            PKG + "ui/rendering/pagers/WeblogEntriesPreviewPager.java",
            PKG + "ui/rendering/pagers/WeblogsPager.java",
            PKG + "ui/rendering/servlets/FeedServlet.java",
            PKG + "ui/rendering/servlets/MediaResourceServlet.java",
            PKG + "ui/rendering/servlets/PageServlet.java",
            PKG + "ui/rendering/servlets/PreviewResourceServlet.java",
            PKG + "ui/rendering/servlets/PreviewServlet.java",
            PKG + "ui/rendering/servlets/RenderingServletUtils.java",
            PKG + "ui/rendering/servlets/ResourceServlet.java",
            PKG + "ui/rendering/servlets/SearchServlet.java",
            PKG + "ui/rendering/util/ParsedRequest.java",
            PKG + "ui/rendering/util/PreviewThemeLookup.java",
            PKG + "ui/rendering/util/WeblogFeedRequest.java",
            PKG + "ui/rendering/util/WeblogPageRequest.java",
            PKG + "ui/rendering/util/WeblogPreviewRequest.java",
            PKG + "ui/rendering/util/WeblogRequest.java",
            PKG + "ui/rendering/util/WeblogSearchRequest.java",
            PKG + "ui/rendering/velocity/RollerResourceLoader.java",
            PKG + "ui/rendering/velocity/ThemeResourceLoader.java",
            PKG + "ui/restapi/auth/ApiTokenAuthFilter.java",
            PKG + "util/MailUtil.java");

    /**
     * The two statics the spec permits to hold a business-tier reference
     * after the wave. Neither holds one today; the allowance is for what the
     * wave adds:
     * <ul>
     *   <li>{@code WebloggerRuntimeConfig} -- the one {@code PropertiesManager}
     *       behind the runtime-config facade, attached at bootstrap. Spec
     *       Decision 8; retired by Stage 2 (configuration as beans).</li>
     *   <li>{@code RollerVelocity} -- the Velocity engine, which carries the
     *       facade as an application attribute for the two resource loaders
     *       Velocity instantiates itself. Spec Decision 4.</li>
     * </ul>
     */
    static final Set<String> STATIC_RESIDUALS = Set.of(
            PKG + "config/WebloggerRuntimeConfig.java",
            PKG + "ui/rendering/velocity/RollerVelocity.java");

    /**
     * Entities under {@code pojos/} that still reference a business-tier type.
     * Emptied by Stage D (plan Tasks 14-17).
     */
    static final Set<String> POJO_ALLOWED = Set.of(
            PKG + "pojos/GlobalPermission.java",
            PKG + "pojos/MediaFile.java",
            PKG + "pojos/User.java",
            PKG + "pojos/Weblog.java",
            PKG + "pojos/WeblogCategory.java",
            PKG + "pojos/WeblogEntry.java",
            PKG + "pojos/WeblogEntryTag.java",
            PKG + "pojos/WeblogPermission.java");

    /**
     * The business-tier types, named explicitly rather than by a
     * {@code *Manager} wildcard (which would catch {@code CacheManager},
     * {@code EntityManager}, ...): the facade, its provider, the URL strategy,
     * the vhost registry, and every return type of a {@code getXManager()}
     * on {@code org.apache.roller.weblogger.business.Weblogger}.
     */
    static final List<String> BUSINESS_TIER_TYPES = List.of(
            "Weblogger", "WebloggerProvider", "URLStrategy", "VirtualHostRegistry",
            "UserManager", "WeblogManager", "WeblogEntryManager", "PropertiesManager",
            "ThreadManager", "IndexManager", "ThemeManager", "PluginManager",
            "MediaFileManager", "FileContentManager", "WeblogPageManager", "EventManager",
            "FormSubmissionManager", "UserTokenManager", "ApiTokenManager");

    /** What an entity may not name: the types above, plus the locator itself and the render seam. */
    private static final Pattern POJO_BANNED = Pattern.compile(
            "\\b(" + String.join("|", BUSINESS_TIER_TYPES)
                    + "|WebloggerFactory|ShortcodeExpander)\\b");

    private static final Pattern SHIM_REFERENCE = Pattern.compile("\\bWebloggerFactory\\b");

    /**
     * A field declaration: {@code static} (any modifiers around it), then the
     * declared type, optional generics, the name, then {@code ;} or {@code =}.
     * Declared-type match only; a {@code static Supplier<Weblogger>} is not a
     * field of type {@code Weblogger} and is not what this rule is about.
     */
    private static final Pattern STATIC_BUSINESS_FIELD = Pattern.compile(
            "\\bstatic\\b[^;=(){}]*?\\b(" + String.join("|", BUSINESS_TIER_TYPES)
                    + ")\\b\\s*(<[^>]*>)?\\s+\\w+\\s*[;=]");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    @Test
    void everyWebloggerFactoryReferenceIsOnTheLedgerAndTheLedgerIsCurrent() throws IOException {
        Set<String> referencing = new TreeSet<>();
        for (Map.Entry<String, String> e : mainSources().entrySet()) {
            if (e.getKey().equals(SHIM)) {
                continue;
            }
            if (SHIM_REFERENCE.matcher(e.getValue()).find()) {
                referencing.add(e.getKey());
            }
        }

        List<String> problems = new ArrayList<>();
        for (String file : referencing) {
            if (!ALLOWED.contains(file)) {
                problems.add(file + " reaches the business tier through WebloggerFactory but is not "
                        + "on StaticServiceLocatorTest.ALLOWED. Inject the dependency instead "
                        + "(constructor, @Lazy Weblogger, or the init hook for models/tasks); "
                        + "this list only ever shrinks.");
            }
        }
        for (String file : new TreeSet<>(ALLOWED)) {
            if (!referencing.contains(file)) {
                problems.add(file + " no longer references WebloggerFactory: remove it from "
                        + "StaticServiceLocatorTest.ALLOWED (the ledger must stay current).");
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void noMainSourceHoldsABusinessTierReferenceInAStaticField() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : mainSources().entrySet()) {
            String file = e.getKey();
            if (file.equals(SHIM) || STATIC_RESIDUALS.contains(file)) {
                continue;
            }
            Matcher m = STATIC_BUSINESS_FIELD.matcher(e.getValue());
            while (m.find()) {
                problems.add(file + " declares a static field of business-tier type "
                        + m.group(1) + " (`" + m.group().trim() + "`). A static holder is the "
                        + "service locator under another name: inject it. The only permitted "
                        + "residuals are " + STATIC_RESIDUALS + " (spec Decisions 4 and 8).");
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void entitiesDoNotReferenceTheBusinessTier() throws IOException {
        String pojos = PKG + "pojos/";
        String wrappers = PKG + "pojos/wrapper/";
        Set<String> referencing = new TreeSet<>();
        for (Map.Entry<String, String> e : mainSources().entrySet()) {
            String file = e.getKey();
            if (!file.startsWith(pojos) || file.startsWith(wrappers)) {
                continue;
            }
            Matcher m = POJO_BANNED.matcher(e.getValue());
            if (m.find()) {
                referencing.add(file + " (" + distinctMatches(m) + ")");
            }
        }

        List<String> problems = new ArrayList<>();
        Set<String> referencingFiles = referencing.stream()
                .map(s -> s.substring(0, s.indexOf(" (")))
                .collect(Collectors.toCollection(TreeSet::new));
        for (String entry : referencing) {
            String file = entry.substring(0, entry.indexOf(" ("));
            if (!POJO_ALLOWED.contains(file)) {
                problems.add(entry + ": an entity may not reach a manager, the facade, the URL "
                        + "strategy or the render seam. Entities are data plus invariants -- "
                        + "move the behaviour to a service (spec Decision 5). POJO_ALLOWED "
                        + "only ever shrinks.");
            }
        }
        for (String file : new TreeSet<>(POJO_ALLOWED)) {
            if (!referencingFiles.contains(file)) {
                problems.add(file + " is clean: remove it from StaticServiceLocatorTest.POJO_ALLOWED.");
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void theScanActuallySeesTheTree() throws IOException {
        // Guards the other three against a silently-empty walk (a moved source
        // root would make every rule pass vacuously).
        Map<String, String> sources = mainSources();
        assertTrue(sources.size() > 400, "expected the full main source tree, saw " + sources.size());
        assertTrue(sources.containsKey(SHIM), "expected to see the shim itself at " + SHIM);
        assertEquals(73, ALLOWED.size(), "ledger seeded at 73 files; edit the set, not this number");
    }

    // ---------------------------------------------------------------------

    /** Repo-relative path -> source with block and line comments stripped. */
    private static Map<String, String> mainSources() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(MAIN)) {
            for (Path p : (Iterable<Path>) paths.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String rel = REPO.relativize(p).toString().replace('\\', '/');
                out.put(rel, stripComments(Files.readString(p)));
            }
        }
        if (out.isEmpty()) {
            fail("no main sources found under " + MAIN);
        }
        return out;
    }

    private static String stripComments(String source) {
        String noBlocks = BLOCK_COMMENT.matcher(source).replaceAll("");
        return LINE_COMMENT.matcher(noBlocks).replaceAll("");
    }

    private static String distinctMatches(Matcher firstFound) {
        Set<String> names = new TreeSet<>();
        names.add(firstFound.group(1));
        while (firstFound.find()) {
            names.add(firstFound.group(1));
        }
        return String.join(", ", names);
    }
}

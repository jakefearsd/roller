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
package org.apache.roller.weblogger.pojos;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Covers the parts of {@link Weblog} that do more than hold a field.
 *
 * <p>Three groups of behaviour matter here. The sanitising setters
 * ({@code setName}, {@code setTagline}, {@code setAbout}) are the last line of
 * defence before those values are written into every page of the blog. The
 * locale and timezone accessors turn user-entered strings into JDK objects and
 * are relied on for every date the blog renders. And the convenience finders
 * that Velocity templates call ({@code getRecentWeblogEntries} and friends)
 * clamp their arguments before hitting the database -- a template asking for
 * a million entries must not get them.
 */
class WeblogLogicTest {

    private Weblog weblog;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");
    }

    // -------------------------------------------------------- sanitisation

    @Test
    void markupIsStrippedFromTheFieldsRenderedOutsideHtmlContexts() {
        // Name, tagline and about are emitted into feeds, <title> and META
        // description tags, none of which can carry markup safely.
        weblog.setName("My <script>alert(1)</script> Blog");
        assertFalse(weblog.getName().contains("<script>"),
                "The weblog name is rendered into the page title and feed metadata; "
                        + "markup must be removed on the way in, not escaped at each use site");

        weblog.setTagline("A <b>bold</b> tagline");
        assertFalse(weblog.getTagline().contains("<b>"),
                "The tagline goes into the feed description and META tags");

        weblog.setAbout("<iframe src='evil'></iframe>All about me");
        assertFalse(weblog.getAbout().contains("<iframe"),
                "The about text is shown on the blog's about page");
        assertTrue(weblog.getAbout().contains("All about me"),
                "Stripping markup must keep the text the author actually wrote");
    }

    @Test
    void plainTextFieldsPassThroughUnchanged() {
        weblog.setName("Plain Name");
        assertEquals("Plain Name", weblog.getName(),
                "Text with no markup must survive untouched -- an over-eager filter that "
                        + "mangled ordinary names would be worse than none");
    }

    // ------------------------------------------------------ locale/timezone

    @Test
    void localeStringIsParsedIntoALocale() {
        weblog.setLocale("en_US");
        assertEquals(Locale.US, weblog.getLocaleInstance(),
                "The stored locale drives date formatting and message lookup for the "
                        + "whole blog");

        weblog.setLocale("fr");
        assertEquals(new Locale("fr"), weblog.getLocaleInstance(),
                "A language-only locale must parse as such rather than being rejected");
    }

    @Test
    void aMissingLocaleFallsBackRatherThanThrowing() {
        weblog.setLocale(null);
        assertEquals(Locale.getDefault(), weblog.getLocaleInstance(),
                "A weblog with no locale must render with the server default; throwing "
                        + "here would take out every page of a half-configured blog");
    }

    @Test
    void aMissingTimeZoneIsFilledInFromTheServerDefault() {
        // getTimeZoneInstance deliberately writes the default back onto the
        // weblog, so the blog stops being ambiguous after the first render.
        weblog.setTimeZone(null);

        TimeZone resolved = weblog.getTimeZoneInstance();

        assertEquals(TimeZone.getDefault(), resolved);
        assertEquals(TimeZone.getDefault().getID(), weblog.getTimeZone(),
                "The resolved zone must be written back onto the weblog, otherwise every "
                        + "call re-resolves and a later server default change silently moves "
                        + "the blog's published timestamps");
    }

    @Test
    void anExplicitTimeZoneIsHonoured() {
        weblog.setTimeZone("America/New_York");
        assertEquals(TimeZone.getTimeZone("America/New_York"), weblog.getTimeZoneInstance());
    }

    // ------------------------------------------------------ defensive copies

    @Test
    void theCreationDateCannotBeMutatedThroughItsAccessor() {
        Date original = new Date(1_000_000L);
        weblog.setDateCreated(original);

        original.setTime(2_000_000L);
        assertEquals(1_000_000L, weblog.getDateCreated().getTime(),
                "setDateCreated must copy: java.util.Date is mutable, and holding a "
                        + "reference to the caller's instance lets them change a persisted "
                        + "field behind the entity's back");

        Date handedOut = weblog.getDateCreated();
        handedOut.setTime(3_000_000L);
        assertEquals(1_000_000L, weblog.getDateCreated().getTime(),
                "getDateCreated must copy on the way out for the same reason");
        assertNotSame(handedOut, weblog.getDateCreated());
    }

    @Test
    void aNullCreationDateStaysNull() {
        weblog.setDateCreated(null);
        assertNull(weblog.getDateCreated(),
                "The defensive copy must not turn a null date into an epoch date");
    }

    @Test
    void copyingOneWeblogOntoAnotherCarriesEveryEditableField() {
        // setData() refreshes a detached weblog from a form bean. A field it
        // forgets is a setting the administrator's edit silently discards, so
        // every one is given a value nothing else in this object has.
        WeblogCategory bloggerCategory = new WeblogCategory();
        bloggerCategory.setName("General");
        WeblogCategory listed = new WeblogCategory();
        listed.setName("Travel");

        Weblog source = new Weblog();
        source.setId("weblog-1");
        source.setHandle("sourceblog");
        source.setName("Source Blog");
        source.setTagline("A tagline");
        source.setCreatorUserName("bob");
        source.setBloggerCategory(bloggerCategory);
        source.setAllowComments(Boolean.FALSE);
        source.setEmailComments(Boolean.TRUE);
        source.setEmailAddress("owner@example.com");
        source.setEditorTheme("journal");
        source.setLocale("fr_FR");
        source.setTimeZone("America/New_York");
        source.setVisible(Boolean.FALSE);
        source.setDateCreated(new Date(1_700_000_000_000L));
        source.setEntryDisplayCount(37);
        source.setActive(Boolean.FALSE);
        source.setLastModified(new Date(1_800_000_000_000L));
        source.setWeblogCategories(new java.util.ArrayList<>(List.of(listed)));

        weblog.setData(source);

        assertEquals("weblog-1", weblog.getId());
        assertEquals("sourceblog", weblog.getHandle());
        assertEquals("Source Blog", weblog.getName());
        assertEquals("A tagline", weblog.getTagline());
        assertEquals("bob", weblog.getCreatorUserName());
        assertSame(bloggerCategory, weblog.getBloggerCategory());
        assertEquals(Boolean.FALSE, weblog.getAllowComments());
        assertEquals(Boolean.TRUE, weblog.getEmailComments());
        assertEquals("owner@example.com", weblog.getEmailAddress());
        assertEquals("journal", weblog.getEditorTheme());
        assertEquals("fr_FR", weblog.getLocale());
        assertEquals("America/New_York", weblog.getTimeZone());
        assertEquals(Boolean.FALSE, weblog.getVisible());
        assertEquals(new Date(1_700_000_000_000L), weblog.getDateCreated());
        assertEquals(37, weblog.getEntryDisplayCount());
        assertEquals(Boolean.FALSE, weblog.getActive());
        assertEquals(new Date(1_800_000_000_000L), weblog.getLastModified());
        assertEquals(List.of(listed), weblog.getWeblogCategories());
    }

    // ----------------------------------------------------------- categories

    @Test
    void categoriesMustBeNamedAndUnique() {
        WeblogCategory general = new WeblogCategory();
        general.setName("General");
        weblog.addCategory(general);

        assertTrue(weblog.hasCategory("General"));
        assertFalse(weblog.hasCategory("Travel"));

        WeblogCategory duplicate = new WeblogCategory();
        duplicate.setName("General");
        IllegalArgumentException clash =
                assertThrows(IllegalArgumentException.class, () -> weblog.addCategory(duplicate),
                        "Two categories with the same name would make the category URL "
                                + "ambiguous, so the duplicate must be rejected outright");
        assertTrue(clash.getMessage().contains("General"),
                "The error must name the offending category: " + clash.getMessage());

        assertThrows(IllegalArgumentException.class, () -> weblog.addCategory(null),
                "A null category must be rejected rather than added and NPE'd on later");
        assertThrows(IllegalArgumentException.class,
                () -> weblog.addCategory(new WeblogCategory()),
                "A category with no name has no URL and must be rejected");

        assertEquals(1, weblog.getWeblogCategories().size(),
                "None of the rejected categories may have been added");
    }

    // ---------------------------------------------------- media directories

    @Test
    void mediaFileDirectoriesAreFoundByName() {
        MediaFileDirectory photos = new MediaFileDirectory();
        photos.setName("photos");
        weblog.getMediaFileDirectories().add(photos);

        assertTrue(weblog.hasMediaFileDirectory("photos"));
        assertSame(photos, weblog.getMediaFileDirectory("photos"));

        assertFalse(weblog.hasMediaFileDirectory("videos"));
        assertNull(weblog.getMediaFileDirectory("videos"),
                "A directory that does not exist must read as null so the caller can "
                        + "create it, rather than returning an arbitrary other directory");
    }

    // ------------------------------------------------------- moderation

    @Test
    void commentModerationIsRequiredIfEitherTheBlogOrTheSiteSaysSo() {
        weblog.setModerateComments(Boolean.TRUE);
        assertTrue(moderationRequiredWithSiteSetting(false),
                "A blog that switched moderation on must get it even when the site has "
                        + "not made moderation compulsory");

        weblog.setModerateComments(Boolean.FALSE);
        assertTrue(moderationRequiredWithSiteSetting(true),
                "A site that made moderation compulsory must override an individual "
                        + "blog's preference -- that switch is what an administrator reaches "
                        + "for during a spam wave");
        assertFalse(moderationRequiredWithSiteSetting(false),
                "With neither switch set, comments are published immediately");
    }

    private boolean moderationRequiredWithSiteSetting(boolean siteWide) {
        Weblogger weblogger = mock(Weblogger.class);
        PropertiesManager properties = mock(PropertiesManager.class);
        when(weblogger.getPropertiesManager()).thenReturn(properties);
        try {
            when(properties.getProperty("users.moderation.required")).thenReturn(
                    new RuntimeConfigProperty("users.moderation.required", String.valueOf(siteWide)));
        } catch (WebloggerException e) {
            throw new AssertionError(e);
        }
        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            return weblog.getCommentModerationRequired();
        }
    }

    // ------------------------------------------------------------- urls

    @Test
    void urlAccessorsAskTheUrlStrategyForTheRightFlavour() {
        URLStrategy urls = mock(URLStrategy.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(urls.getWeblogURL(weblog, null, false)).thenReturn("/roller/testblog/");
        when(urls.getWeblogURL(weblog, null, true)).thenReturn("http://example.com/roller/testblog/");

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertEquals("/roller/testblog/", weblog.getURL(),
                    "getURL() must ask for the context-relative form");
            assertEquals("http://example.com/roller/testblog/", weblog.getAbsoluteURL(),
                    "getAbsoluteURL() must ask for the absolute form -- feeds and "
                            + "notification e-mails are read outside the site and need it");
        }
    }

    // -------------------------------------------------- recent entry finders

    /*
     * The upper bound on these finders ("length > 100 ? 100 : length") has a
     * boundary that no input can pin: at exactly 100, clamping and not clamping
     * both yield 100. Mutation testing reports the boundary as surviving on all
     * three finders; it is an equivalent mutant. The lower bound is a different
     * matter -- at exactly 1 the two answers differ, so that one is asserted
     * below.
     */

    @Test
    void recentEntryFindersRefuseToAskForMoreThanAHundredEntries() throws Exception {
        // These are reachable straight from a Velocity template, so the bound is
        // the only thing stopping a theme from selecting an entire blog.
        WeblogEntrySearchCriteria criteria = captureRecentEntryQuery("General", 5000);

        assertEquals(100, criteria.getMaxResults(),
                "A template asking for 5000 entries must be clamped to 100; without the "
                        + "clamp a single page render could pull the whole weblogentry table");
        assertEquals(PubStatus.PUBLISHED, criteria.getStatus(),
                "Only published entries may be exposed through a template helper");
        assertEquals("General", criteria.getCatName());
        assertSame(weblog, criteria.getWeblog());
    }

    @Test
    void theNilCategorySentinelMeansNoCategoryRestriction() throws Exception {
        // Velocity has no null literal, so themes pass the string "nil".
        WeblogEntrySearchCriteria criteria = captureRecentEntryQuery("nil", 10);

        assertNull(criteria.getCatName(),
                "The literal string 'nil' must be translated to 'no restriction'; leaving "
                        + "it in place would search for a category actually named nil and "
                        + "return nothing");
        assertEquals(10, criteria.getMaxResults(),
                "A request within the limit must be passed through unchanged");
    }

    @Test
    void askingForASingleEntryIsAValidRequest() throws Exception {
        // One is the smallest useful request and sits directly on the "too few"
        // boundary; a sidebar showing the latest post asks for exactly this.
        WeblogEntrySearchCriteria criteria = captureRecentEntryQuery("General", 1);

        assertEquals(1, criteria.getMaxResults(),
                "A request for one entry must reach the database, not be rejected as if "
                        + "it were a request for none");
    }

    @Test
    void resultsFromThePersistenceLayerAreReturnedUnchanged() throws Exception {
        WeblogEntry first = new WeblogEntry();
        first.setAnchor("first");
        WeblogEntry second = new WeblogEntry();
        second.setAnchor("second");

        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getWeblogEntries(any())).thenReturn(List.of(first, second));

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertEquals(List.of(first, second), weblog.getRecentWeblogEntries("General", 10),
                    "The finder must hand back what the query returned; an empty list "
                            + "would render as a blog with no posts");
            assertEquals(List.of(first, second), weblog.getRecentWeblogEntriesByTag("java", 10));
        }
    }

    @Test
    void askingForNoEntriesDoesNotTouchTheDatabase() throws Exception {
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertEquals(List.of(), weblog.getRecentWeblogEntries("General", 0),
                    "A length of zero means no entries were asked for");
            assertEquals(List.of(), weblog.getRecentWeblogEntries("General", -1),
                    "A negative length must not be treated as 'unlimited'");
            assertEquals(List.of(), weblog.getRecentWeblogEntriesByTag("java", 0));
        }
        org.mockito.Mockito.verifyNoInteractions(entries);
    }

    @Test
    void tagFinderTranslatesTheNilSentinelAndClampsTheLimit() throws Exception {
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getWeblogEntries(any())).thenReturn(List.of());
        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            weblog.getRecentWeblogEntriesByTag("nil", 5000);
            weblog.getRecentWeblogEntriesByTag("java", 3);
            weblog.getRecentWeblogEntriesByTag("java", 1);
        }

        org.mockito.Mockito.verify(entries, org.mockito.Mockito.times(3))
                .getWeblogEntries(captor.capture());
        List<WeblogEntrySearchCriteria> issued = captor.getAllValues();

        assertEquals(List.of(), issued.get(0).getTags(),
                "'nil' means 'any tag', so no tag restriction may be sent");
        assertEquals(100, issued.get(0).getMaxResults(), "The 100 entry ceiling applies here too");
        assertEquals(List.of("java"), issued.get(1).getTags());
        assertEquals(3, issued.get(1).getMaxResults());
        assertSame(weblog, issued.get(1).getWeblog(),
                "The query must be scoped to this weblog; without it a tag page would "
                        + "show every blog's posts on the server");
        assertEquals(PubStatus.PUBLISHED, issued.get(1).getStatus(),
                "and to published entries only, or drafts appear on the public tag page");
        assertEquals(1, issued.get(2).getMaxResults(),
                "Asking for a single entry must reach the database rather than being "
                        + "rejected as if it were a request for none");
    }

    private WeblogEntrySearchCriteria captureRecentEntryQuery(String category, int length)
            throws Exception {
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getWeblogEntries(any())).thenReturn(List.of());
        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            weblog.getRecentWeblogEntries(category, length);
        }

        org.mockito.Mockito.verify(entries).getWeblogEntries(captor.capture());
        return captor.getValue();
    }

    // -------------------------------------------------------- counts, tags

    @Test
    void countsFailSoftWhenThePersistenceLayerErrors() throws Exception {
        // These are called from templates. An exception escaping here takes out
        // the whole page, so the accessors swallow it and report nothing.
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getEntryCount(weblog)).thenThrow(new WebloggerException("boom"));

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertEquals(0L, weblog.getEntryCount());
        }
    }

    /*
     * getPopularTags builds its start date with Calendar.getInstance() followed
     * by setTime(new Date()). The Calendar is already set to now, so removing
     * the setTime call changes nothing -- mutation testing reports it as
     * surviving, and it is an equivalent mutant (the line is redundant).
     */

    @Test
    void popularTagsWindowIsOnlyAppliedWhenAPositiveNumberOfDaysIsGiven() throws Exception {
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getPopularTags(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        ArgumentCaptor<Date> startDate = ArgumentCaptor.forClass(Date.class);

        TagStat popular = new TagStat();
        popular.setName("java");
        when(entries.getPopularTags(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(popular));

        List<TagStat> returned;
        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            returned = weblog.getPopularTags(-1, 10);
            weblog.getPopularTags(0, 10);
            weblog.getPopularTags(30, 10);
        }

        org.mockito.Mockito.verify(entries, org.mockito.Mockito.times(3))
                .getPopularTags(eq(weblog), startDate.capture(), eq(0), eq(10));
        List<Date> starts = startDate.getAllValues();

        assertEquals(List.of(popular), returned,
                "The tag cloud must render the tags the query returned");
        assertNull(starts.get(0),
                "-1 days means 'all time', which has to reach the query as a null start "
                        + "date rather than a date 1 day in the past");
        assertNull(starts.get(1),
                "0 days means 'all time' too -- treating it as a zero-length window would "
                        + "produce an empty tag cloud rather than the whole history");
        long thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30);
        assertTrue(Math.abs(starts.get(2).getTime() - thirtyDaysAgo) < TimeUnit.MINUTES.toMillis(5),
                "A 30 day window must start 30 days ago, not 30 days in the future: "
                        + starts.get(2));
    }

    @Test
    void namedCategoryLookupFallsBackToTheFirstCategoryForTheNilSentinel() throws Exception {
        WeblogCategory general = new WeblogCategory();
        general.setName("General");
        weblog.getWeblogCategories().add(general);

        WeblogCategory travel = new WeblogCategory();
        travel.setName("Travel");
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getWeblogCategoryByName(weblog, "Travel")).thenReturn(travel);

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertSame(travel, weblog.getWeblogCategory("Travel"),
                    "A named category must be looked up by name");
            assertSame(general, weblog.getWeblogCategory("nil"),
                    "The 'nil' sentinel means 'whichever category comes first', which is "
                            + "how templates render an unfiltered category list");
            assertSame(general, weblog.getWeblogCategory(null),
                    "A null name behaves the same as the sentinel");
        }
    }

    @Test
    void themeLookupFailsSoftlyRatherThanBreakingTheRender() throws Exception {
        org.apache.roller.weblogger.business.themes.ThemeManager themes =
                mock(org.apache.roller.weblogger.business.themes.ThemeManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getThemeManager()).thenReturn(themes);
        when(themes.getTheme(weblog)).thenThrow(new WebloggerException("no such theme"));

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertNull(weblog.getTheme(),
                    "A weblog pointing at a theme that no longer exists must report no "
                            + "theme rather than propagating out of the accessor");
        }
    }

    @Test
    void weblogEntryLookupByAnchorReturnsTheEntryAndFailsSoftly() throws Exception {
        WeblogEntry found = new WeblogEntry();
        found.setAnchor("hello-world");

        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getWeblogEntryByAnchor(weblog, "hello-world")).thenReturn(found);
        when(entries.getWeblogEntryByAnchor(eq(weblog), isNull()))
                .thenThrow(new WebloggerException("bad anchor"));

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertSame(found, weblog.getWeblogEntry("hello-world"),
                    "A successful lookup must hand back the entry it found");
            assertNull(weblog.getWeblogEntry(null),
                    "A lookup failure must read as 'no such entry' so the permalink page "
                            + "can render a 404 instead of a stack trace");
        }
    }

    @Test
    void countsReportWhatThePersistenceLayerSays() throws Exception {
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getEntryCount(weblog)).thenReturn(13L);

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertEquals(13L, weblog.getEntryCount(),
                    "The counts are rendered in the admin blog list; reporting a constant "
                            + "would make every blog look identical");
        }
    }

    @Test
    void themeLookupReturnsTheThemeTheManagerResolved() throws Exception {
        WeblogTheme resolved = mock(WeblogTheme.class);
        org.apache.roller.weblogger.business.themes.ThemeManager themes =
                mock(org.apache.roller.weblogger.business.themes.ThemeManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getThemeManager()).thenReturn(themes);
        when(themes.getTheme(weblog)).thenReturn(resolved);

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertSame(resolved, weblog.getTheme(),
                    "The weblog must render with the theme the ThemeManager resolved for it");
        }
    }
}

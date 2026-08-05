/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.util.Collections;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WeblogConfigController} and {@link WeblogConfigBean}.
 *
 * <p>This form writes settings that govern the blog's public behaviour, so the
 * checks that matter are the ones that stop a setting from being applied: the
 * entry-display cap (a blog asking for 10,000 entries per page would render
 * itself into a timeout) and the comment/language invariants the controller
 * enforces after copying the form onto the weblog.
 */
class WeblogConfigControllerTest extends EditorControllerTestSupport {

    private WeblogConfigController controller;
    private WeblogConfigBean bean;
    private Model model;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new WeblogConfigController());
        bean = new WeblogConfigBean();
        model = newModel();

        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenReturn(Collections.emptyList());
        when(weblogger.getPluginManager().getWeblogEntryPlugins(weblog))
                .thenReturn(Collections.emptyMap());
        // The display-count cap is a runtime property; unstubbed it reads as -1,
        // which would reject every possible value.
        givenRuntimeProperty("site.pages.maxEntries", "30");

        weblog.setActive(Boolean.TRUE);
        weblog.setShowAllLangs(true);
        bean.copyFrom(weblog);
    }

    // --- viewing ---

    @Test
    void openingTheFormLoadsTheWeblogsCurrentSettings() {
        weblog.setName("My Blog");
        weblog.setTagline("A tagline");
        weblog.setEntryDisplayCount(25);

        String view = controller.execute(request, model, bean);

        assertEquals(".WeblogConfig", view);
        assertEquals("My Blog", bean.getName());
        assertEquals("A tagline", bean.getTagline());
        assertEquals(25, bean.getEntryDisplayCount());
        assertEquals(WEBLOG_HANDLE, bean.getHandle());
    }

    // --- saving ---

    @Test
    void savingAppliesTheFormToTheWeblogAndPersistsIt() throws Exception {
        bean.setName("Renamed Blog");
        bean.setTagline("New tagline");
        bean.setEmailAddress("owner@example.com");
        bean.setEntryDisplayCount(20);

        String view = controller.save(request, model, bean);

        assertEquals(".WeblogConfig", view);
        assertEquals("Renamed Blog", weblog.getName());
        assertEquals("New tagline", weblog.getTagline());
        assertEquals("owner@example.com", weblog.getEmailAddress());
        assertEquals(20, weblog.getEntryDisplayCount());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(messages(model).contains("websiteSettings.savedChanges"),
                "Expected a save confirmation, got: " + messages(model));
    }

    /**
     * A weblog with no blogger category must still be savable.
     *
     * <p>{@code removeWeblogCategory} nulls a weblog's blogger category when
     * that category is deleted, so this is a state the product creates for
     * itself. The save handler then dereferenced it unconditionally and threw,
     * and the catch turned that into "Error updating configuration" -- for
     * every save, forever, with nothing in the UI to put it right. A browser
     * test that merely toggled a checkbox on the settings page is what found
     * it.
     */
    @Test
    void savingAWeblogThatHasNoBloggerCategoryStillWorks() throws Exception {
        weblog.setBloggerCategory(null);
        WeblogCategory chosen = category("cat-1", "General");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(chosen);
        bean.setBloggerCategoryId("cat-1");
        bean.setName("Renamed Blog");

        controller.save(request, model, bean);

        assertEquals(chosen, weblog.getBloggerCategory(),
                "the posted category must be applied rather than throwing");
        assertEquals("Renamed Blog", weblog.getName());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(messages(model).contains("websiteSettings.savedChanges"),
                "Expected a save confirmation, got: " + messages(model));
    }

    @Test
    void savingLeavesTheBloggerCategoryAloneWhenItAlreadyMatches() throws Exception {
        WeblogCategory existing = category("cat-1", "General");
        weblog.setBloggerCategory(existing);
        bean.setBloggerCategoryId("cat-1");

        controller.save(request, model, bean);

        assertEquals(existing, weblog.getBloggerCategory());
        verify(weblogger.getWeblogEntryManager(), never()).getWeblogCategory(anyString());
    }

    @Test
    void savingNeverChangesTheWeblogHandle() throws Exception {
        // The handle is the blog's URL and its identity in every permission
        // row; copyTo deliberately does not carry it across.
        bean.setHandle("hijacked");

        controller.save(request, model, bean);

        assertEquals(WEBLOG_HANDLE, weblog.getHandle(),
                "A posted handle must not be able to rename the blog out from under its URLs");
    }

    @Test
    void anEntryDisplayCountAboveTheSiteCapIsRefused() throws Exception {
        // Beyond the cap the front page renders unboundedly many entries.
        bean.setEntryDisplayCount(500);

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.error.entryDisplayCount"),
                "Expected an entry-display-count error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void anEntryDisplayCountExactlyAtTheSiteCapIsAccepted() throws Exception {
        // The check is strictly greater-than; the cap itself must be usable.
        bean.setEntryDisplayCount(30);

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "The cap itself must be allowed: " + errors(model));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void deactivatingAWeblogAlsoTurnsOffItsComments() throws Exception {
        // An inactive blog is not served, so leaving comments on would keep
        // accepting them against content nobody can read.
        bean.setActive(false);
        bean.setAllowComments(true);

        controller.save(request, model, bean);

        assertFalse(weblog.getAllowComments(),
                "Deactivating a weblog must close its comments");
        assertTrue(messages(model).contains("websiteSettings.commentsOffForInactiveWeblog"),
                "The owner must be told comments were turned off: " + messages(model));
    }

    @Test
    void anActiveWeblogKeepsItsCommentSetting() throws Exception {
        bean.setActive(true);
        bean.setAllowComments(true);

        controller.save(request, model, bean);

        assertTrue(weblog.getAllowComments());
        assertFalse(messages(model).contains("websiteSettings.commentsOffForInactiveWeblog"));
    }

    @Test
    void turningOffBothLanguageOptionsIsNotAllowedToLeaveTheBlogShowingNothing() throws Exception {
        // showAllLangs=false means "only show the weblog's own locale", which
        // only makes sense when multi-language mode is on. With both off the
        // blog would filter out every entry, so multi-language is forced on.
        bean.setShowAllLangs(false);
        bean.setEnableMultiLang(false);

        controller.save(request, model, bean);

        assertTrue(weblog.isEnableMultiLang(),
                "With showAllLangs off, multi-language mode must be forced on so the blog "
                        + "still renders entries");
    }

    @Test
    void theUsualLanguageCombinationIsLeftAlone() throws Exception {
        bean.setShowAllLangs(true);
        bean.setEnableMultiLang(false);

        controller.save(request, model, bean);

        assertFalse(weblog.isEnableMultiLang(),
                "The default single-language configuration must not be silently upgraded");
    }

    @Test
    void analyticsCodeIsTrimmedBeforeStorage() throws Exception {
        // The code is injected verbatim into every rendered page.
        bean.setAnalyticsCode("  <script>tracker()</script>  ");

        controller.save(request, model, bean);

        assertEquals("<script>tracker()</script>", weblog.getAnalyticsCode());
    }

    @Test
    void aNullAnalyticsCodeIsLeftNullRatherThanTrimmed() throws Exception {
        bean.setAnalyticsCode(null);

        controller.save(request, model, bean);

        assertNull(weblog.getAnalyticsCode());
    }

    @Test
    void applyingCommentDefaultsRewritesExistingEntriesOnlyWhenAsked() throws Exception {
        bean.setApplyCommentDefaults(true);

        controller.save(request, model, bean);

        verify(weblogger.getWeblogEntryManager()).applyCommentDefaultsToEntries(weblog);
    }

    @Test
    void existingEntriesAreLeftAloneWhenCommentDefaultsAreNotApplied() throws Exception {
        // This is a bulk rewrite of every entry on the blog; it must be opt-in.
        bean.setApplyCommentDefaults(false);

        controller.save(request, model, bean);

        verify(weblogger.getWeblogEntryManager(), never()).applyCommentDefaultsToEntries(any());
    }

    @Test
    void changingTheBloggerApiCategoryLooksUpTheNewOne() throws Exception {
        WeblogCategory current = category("cat-1", "Travel");
        WeblogCategory replacement = category("cat-2", "Food");
        weblog.setBloggerCategory(current);
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-2")).thenReturn(replacement);
        bean.setBloggerCategoryId("cat-2");

        controller.save(request, model, bean);

        assertEquals(replacement, weblog.getBloggerCategory());
    }

    @Test
    void leavingTheBloggerApiCategoryUnchangedDoesNotReLookItUp() throws Exception {
        WeblogCategory current = category("cat-1", "Travel");
        weblog.setBloggerCategory(current);
        bean.setBloggerCategoryId("cat-1");

        controller.save(request, model, bean);

        assertEquals(current, weblog.getBloggerCategory());
        verify(weblogger.getWeblogEntryManager(), never()).getWeblogCategory(any());
    }

    @Test
    void aFailedSaveIsReportedRatherThanConfirmed() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogManager()).saveWeblog(any());

        controller.save(request, model, bean);

        assertFalse(messages(model).contains("websiteSettings.savedChanges"),
                "A failed save must not report success");
        assertTrue(errors(model).size() >= 1,
                "Expected the failure to be surfaced, got: " + errors(model));
    }

    // --- WeblogConfigBean ---

    @Test
    void beanRoundTripsTheSettingsItOwns() {
        Weblog source = new Weblog();
        source.setHandle("source");
        source.setName("Source Blog");
        source.setTagline("Tag");
        source.setEmailAddress("a@example.com");
        source.setLocale("fr_FR");
        source.setTimeZone("Asia/Tokyo");
        source.setEntryDisplayCount(7);
        source.setDefaultCommentDays(14);
        source.setDefaultPlugins("A,B");
        source.setAbout("About me");
        source.setIconPath("icon.png");
        source.setActive(Boolean.TRUE);
        source.setEnableMultiLang(true);
        source.setShowAllLangs(false);

        WeblogConfigBean copy = new WeblogConfigBean();
        copy.copyFrom(source);

        assertEquals("source", copy.getHandle());
        assertEquals("Source Blog", copy.getName());
        assertEquals("fr_FR", copy.getLocale());
        assertEquals("Asia/Tokyo", copy.getTimeZone());
        assertEquals(7, copy.getEntryDisplayCount());
        assertEquals("14", copy.getDefaultCommentDays(),
                "The dropdown binds a string, so the numeric setting is stringified");
        assertEquals(2, copy.getDefaultPluginsArray().length);
        assertEquals("About me", copy.getAbout());
        assertEquals("icon.png", copy.getIcon());

        Weblog target = new Weblog();
        copy.copyTo(target);

        assertEquals("Source Blog", target.getName());
        assertEquals("Asia/Tokyo", target.getTimeZone());
        assertEquals(14, target.getDefaultCommentDays());
        assertEquals("A,B", target.getDefaultPlugins(),
                "The checkbox array must be rejoined into the stored csv");
        assertNull(target.getHandle(), "copyTo must never write the handle");
    }

    /**
     * The sign-in requirement has to survive the round trip in both directions.
     *
     * <p>It is the setting that decides whether an anonymous stranger can write
     * to the database, so a copyFrom or copyTo that quietly dropped it would
     * either show a moderator the wrong state or reset the weblog to the bean's
     * default the next time anything else on the page was saved.
     */
    @Test
    void theSignInRequirementSurvivesTheRoundTripInBothStates() {
        Weblog source = new Weblog();
        source.setRequireAuthenticatedComments(Boolean.FALSE);

        WeblogConfigBean opened = new WeblogConfigBean();
        opened.copyFrom(source);
        assertFalse(opened.getRequireAuthenticatedComments(),
                "a weblog that accepts anonymous comments must show the box unticked");

        Weblog target = new Weblog();
        opened.copyTo(target);
        assertEquals(Boolean.FALSE, target.getRequireAuthenticatedComments());

        source.setRequireAuthenticatedComments(Boolean.TRUE);
        opened.copyFrom(source);
        assertTrue(opened.getRequireAuthenticatedComments());
        opened.copyTo(target);
        assertEquals(Boolean.TRUE, target.getRequireAuthenticatedComments());
    }

    /**
     * New weblogs require a sign-in; the form bean does not.
     *
     * <p>The two defaults have to disagree. An unticked HTML checkbox posts no
     * parameter at all, so whatever the bean field starts as is exactly what
     * "unticked" means on save -- a bean defaulting to true would make the
     * setting impossible to turn off, which is how this was first written and
     * what a browser test caught. The weblog's own default is the one that
     * decides what a brand new blog does.
     */
    @Test
    void aBrandNewWeblogRequiresASignInButAnUntickedBoxTurnsItOff() {
        assertEquals(Boolean.TRUE, new Weblog().getRequireAuthenticatedComments(),
                "a weblog created before anyone visits its settings page must not be "
                        + "open to anonymous comments");

        Weblog closed = new Weblog();
        closed.setRequireAuthenticatedComments(Boolean.TRUE);

        // Exactly what Spring hands the save handler when the box is unticked:
        // a fresh bean with nothing bound into that field.
        new WeblogConfigBean().copyTo(closed);

        assertEquals(Boolean.FALSE, closed.getRequireAuthenticatedComments(),
                "an unticked box must actually turn the requirement off");
    }

    @Test
    void beanCopyFromLeavesTheBloggerCategoryUnsetWhenTheWeblogHasNone() {
        Weblog source = new Weblog();
        source.setBloggerCategory(null);

        WeblogConfigBean copy = new WeblogConfigBean();
        copy.copyFrom(source);

        assertNull(copy.getBloggerCategoryId());
    }

    private WeblogCategory category(String id, String name) {
        WeblogCategory category = new WeblogCategory();
        category.setId(id);
        category.setName(name);
        category.setWeblog(weblog);
        return category;
    }
}

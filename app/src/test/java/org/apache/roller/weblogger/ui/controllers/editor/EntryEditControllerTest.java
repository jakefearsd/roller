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

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.ShareLink;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntryEditController}.
 *
 * <p>This controller decides, for every save, what publication state an entry
 * lands in and what the rest of the system is told about it. Those decisions
 * carry real consequences: the wrong status makes a draft public or hides a
 * published post, a missed search-index call leaves deleted content findable,
 * and the pinned-to-main flag is a site-wide privilege that only global admins
 * may exercise. The tests below drive one decision each and assert on the side
 * effect, not merely on the view name.
 */
class EntryEditControllerTest extends EditorControllerTestSupport {

    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    private EntryEditController controller;
    private EntryBean bean;
    private Model model;
    private WeblogCategory category;

    /** Whether the authenticated user holds WeblogPermission.POST on the weblog. */
    private boolean userMayPost = true;
    /** Whether the authenticated user is a global administrator. */
    private boolean userIsGlobalAdmin;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new EntryEditController());
        bean = new EntryBean();
        model = newModel();

        category = new WeblogCategory();
        category.setId("cat-1");
        category.setName("Travel");
        category.setWeblog(weblog);
        weblog.getWeblogCategories().add(category);
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenReturn(java.util.List.of(category));
        when(weblogger.getWeblogEntryManager().getComments(any())).thenReturn(Collections.emptyList());
        when(weblogger.getPluginManager().getWeblogEntryPlugins(weblog))
                .thenReturn(java.util.Collections.emptyMap());

        // Both the weblog POST check and the global-admin check funnel through
        // UserManager.checkPermission, so the stub has to tell them apart.
        when(weblogger.getUserManager().checkPermission(any(), any())).thenAnswer(invocation -> {
            Object permission = invocation.getArgument(0);
            if (permission instanceof GlobalPermission) {
                return userIsGlobalAdmin;
            }
            if (permission instanceof WeblogPermission weblogPermission) {
                return userMayPost || !weblogPermission.hasAction(WeblogPermission.POST);
            }
            return false;
        });

        // The editor builds a preview link for any entry that has an id, and
        // WeblogEntry generates one in its constructor, so this is on every path.
        when(weblogger.getUrlStrategy().getPreviewURLStrategy(any()))
                .thenReturn(org.mockito.Mockito.mock(
                        org.apache.roller.weblogger.business.URLStrategy.class));

        bean.setCategoryId("cat-1");
        bean.setTitle("A new post");
        bean.setText("Body text");
    }

    // --- entryAdd, GET ---

    @Test
    void openingTheNewEntryFormSeedsTheBeanFromTheWeblogsDefaults() {
        weblog.setLocale("fr_FR");
        weblog.setDefaultAllowComments(Boolean.TRUE);
        weblog.setDefaultCommentDays(30);
        weblog.setDefaultPlugins("ConvertLineBreaks,TextileFormatter");

        String view = controller.entryAddExecute(request, model, bean);

        assertEquals(".EntryEdit", view);
        assertEquals("fr_FR", bean.getLocale());
        assertTrue(bean.getAllowComments());
        assertEquals(30, bean.getCommentDays());
        assertEquals(2, bean.getPlugins().length,
                "The weblog's default plugin csv must be split onto the new entry");
    }

    @Test
    void openingTheNewEntryFormLeavesPluginsUnsetWhenTheWeblogHasNoDefaults() {
        weblog.setDefaultPlugins(null);

        controller.entryAddExecute(request, model, bean);

        assertNull(bean.getPlugins(),
                "A weblog with no default plugins must not produce a bogus plugin array");
    }

    @Test
    void openingTheNewEntryFormAttributesTheDraftToTheLoggedInUser() {
        controller.entryAddExecute(request, model, bean);

        WeblogEntry entry = (WeblogEntry) model.getAttribute("entry");
        assertNotNull(entry);
        assertEquals(USER_NAME, entry.getCreatorUserName());
        assertEquals(weblog, entry.getWebsite());
    }

    // --- entryAdd, save ---

    @Test
    void savingANewDraftStoresItAsADraftAndRedirectsToTheEditor() throws Exception {
        String view = controller.entryAddSaveDraft(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.DRAFT, saved.getStatus());
        assertEquals("redirect:/roller-ui/authoring/entryEdit!firstSave.rol?weblog="
                + WEBLOG_HANDLE + "&bean.id=" + saved.getId(), view,
                "After the first save the user must land on the edit form for the entry that "
                        + "was actually persisted, addressed by its own id");
        assertEquals(saved.getId(), bean.getId(),
                "The bean must carry the new id into the redirect");
    }

    @Test
    void savingANewDraftDoesNotAddItToTheSearchIndex() throws Exception {
        controller.entryAddSaveDraft(request, model, bean);

        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any(WeblogEntry.class));
        verify(weblogger.getIndexManager(), never()).removeEntryIndexOperation(any(WeblogEntry.class));
    }

    @Test
    void publishingANewEntryStoresItAsPublishedAndIndexesIt() throws Exception {
        userMayPost = true;

        controller.entryAddPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.PUBLISHED, saved.getStatus());
        verify(weblogger.getIndexManager()).addEntryReIndexOperation(saved);
    }

    @Test
    void publishingWithNoDateGivenStampsThePubTimeWithTheUpdateTime() throws Exception {
        // Otherwise a published entry would have a null pub time and sort to
        // the bottom of (or fall out of) every date-ordered feed.
        userMayPost = true;
        bean.setDateString(null);

        controller.entryAddPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertNotNull(saved.getPubTime(), "A published entry must have a publication time");
        assertEquals(saved.getUpdateTime(), saved.getPubTime());
    }

    @Test
    void publishingWithoutPostPermissionSubmitsForReviewInstead() throws Exception {
        // A contributor with only EDIT_DRAFT must never be able to publish
        // straight to the live blog.
        userMayPost = false;

        controller.entryAddPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.PENDING, saved.getStatus(),
                "A user without POST permission must land in PENDING, not PUBLISHED");
        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any(WeblogEntry.class));
    }

    @Test
    void publishingWithAFutureDateSchedulesTheEntry() throws Exception {
        userMayPost = true;
        setBeanPubTime(new Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000));

        controller.entryAddPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.SCHEDULED, saved.getStatus());
        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any(WeblogEntry.class));
    }

    @Test
    void publishingWithAPastDateBackdatesRatherThanSchedules() throws Exception {
        userMayPost = true;
        setBeanPubTime(new Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000));

        controller.entryAddPublish(request, model, bean);

        assertEquals(PubStatus.PUBLISHED, captureSavedEntry().getStatus(),
                "Back-dating an entry must publish it immediately, not schedule it in the past");
    }

    @Test
    void aDateInsideTheSchedulingThresholdPublishesImmediately() throws Exception {
        // The controller only treats a time as "scheduled" when it is more than
        // MIN_IN_MS ahead, so that clicking publish with the current minute
        // still in the date box does not park the entry in the scheduler.
        userMayPost = true;
        setBeanPubTime(new Date(System.currentTimeMillis() + RollerConstants.MIN_IN_MS / 2));

        controller.entryAddPublish(request, model, bean);

        assertEquals(PubStatus.PUBLISHED, captureSavedEntry().getStatus());
    }

    // --- entryEdit ---

    @Test
    void openingAnEntryThatDoesNotExistBouncesToTheMenu() throws Exception {
        bean.setId("gone");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("gone")).thenReturn(null);

        assertEquals("redirect:/roller-ui/menu.rol",
                controller.entryEditExecute(request, model, bean),
                "A missing entry must not render the editor against a null entry");
    }

    @Test
    void openingAnEntryWithNoIdBouncesToTheMenu() {
        bean.setId(null);

        assertEquals("redirect:/roller-ui/menu.rol",
                controller.entryEditExecute(request, model, bean));
    }

    @Test
    void openingAnEntryWhoseLookupFailsBouncesToTheMenu() throws Exception {
        bean.setId("entry-1");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1"))
                .thenThrow(new WebloggerException("database down"));

        assertEquals("redirect:/roller-ui/menu.rol",
                controller.entryEditExecute(request, model, bean),
                "A lookup failure must be handled, not propagated as a 500");
    }

    @Test
    void openingAnExistingEntryLoadsItIntoTheForm() throws Exception {
        WeblogEntry existing = existingEntry(PubStatus.PUBLISHED);
        existing.setTitle("Stored title");

        String view = controller.entryEditExecute(request, model, bean);

        assertEquals(".EntryEdit", view);
        assertEquals("Stored title", bean.getTitle());
        assertEquals(PubStatus.PUBLISHED.name(), bean.getStatus());
        assertEquals(existing, model.getAttribute("entry"));
    }

    @Test
    void firstSaveShowsTheStatusMessageForTheEntryThatWasJustCreated() throws Exception {
        existingEntry(PubStatus.DRAFT);

        controller.entryEditFirstSave(request, model, bean);

        assertTrue(messages(model).contains("weblogEdit.draftSaved"),
                "The landing page after a first save must confirm what happened: " + messages(model));
    }

    @Test
    void revertingAPublishedEntryToDraftFlagsTheAggregatesForRefresh() throws Exception {
        // Un-publishing changes the site's entry counts, so the aggregate
        // tables have to be recomputed. Missing this leaves stale counts on the
        // front page indefinitely.
        existingEntry(PubStatus.PUBLISHED);

        controller.entryEditSaveDraft(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.DRAFT, saved.getStatus());
        assertTrue(saved.getRefreshAggregates(),
                "Taking an entry off the site must trigger an aggregate refresh");
    }

    @Test
    void savingAnAlreadyDraftEntryAsDraftDoesNotTouchTheAggregates() throws Exception {
        existingEntry(PubStatus.DRAFT);

        controller.entryEditSaveDraft(request, model, bean);

        assertFalse(captureSavedEntry().getRefreshAggregates(),
                "A draft-to-draft save changes no published counts, so it must not force a refresh");
    }

    @Test
    void unpublishingAnEntryRemovesItFromTheSearchIndex() throws Exception {
        // If this is skipped, content the author has pulled offline keeps
        // turning up in site search.
        existingEntry(PubStatus.PUBLISHED);

        controller.entryEditSaveDraft(request, model, bean);

        verify(weblogger.getIndexManager()).removeEntryIndexOperation(any(WeblogEntry.class));
        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any(WeblogEntry.class));
    }

    @Test
    void publishingAPreviouslyUnpublishedEntryFlagsTheAggregatesForRefresh() throws Exception {
        existingEntry(PubStatus.DRAFT);
        userMayPost = true;

        controller.entryEditPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.PUBLISHED, saved.getStatus());
        assertTrue(saved.getRefreshAggregates(),
                "Putting an entry on the site must trigger an aggregate refresh");
        verify(weblogger.getIndexManager()).addEntryReIndexOperation(saved);
    }

    @Test
    void schedulingAnAlreadyPublishedEntryFlagsTheAggregatesForRefresh() throws Exception {
        // Pushing a live post into the future takes it off the site now, so the
        // published counts have to be recomputed just as un-publishing does.
        existingEntry(PubStatus.PUBLISHED);
        userMayPost = true;
        setBeanPubTime(new Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000));

        controller.entryEditPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.SCHEDULED, saved.getStatus());
        assertTrue(saved.getRefreshAggregates(),
                "Scheduling a currently-published entry removes it from the site now");
    }

    @Test
    void schedulingAnUnpublishedEntryDoesNotTouchTheAggregates() throws Exception {
        existingEntry(PubStatus.DRAFT);
        userMayPost = true;
        setBeanPubTime(new Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000));

        controller.entryEditPublish(request, model, bean);

        assertFalse(captureSavedEntry().getRefreshAggregates(),
                "A draft was never counted, so scheduling it changes no totals");
    }

    @Test
    void aSuccessfulSaveIsCommitted() throws Exception {
        // Everything above verifies the manager was called; without a flush
        // none of it reaches the database.
        controller.entryAddSaveDraft(request, model, bean);

        assertEquals(1, weblogger.flushCount(), "A successful save must be committed");
    }

    @Test
    void aFailedSaveIsNotCommitted() throws Exception {
        doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).saveWeblogEntry(any());

        controller.entryAddSaveDraft(request, model, bean);

        assertEquals(0, weblogger.flushCount());
    }

    @Test
    void theEditorOffersTheFullRangeOfPublicationTimes() throws Exception {
        // These populate the hour/minute/second selectors next to the date box.
        // A truncated range silently makes part of the day unschedulable.
        controller.entryAddExecute(request, model, bean);

        assertEquals(24, ((java.util.List<?>) model.getAttribute("hoursList")).size());
        assertEquals(60, ((java.util.List<?>) model.getAttribute("minutesList")).size());
        assertEquals(60, ((java.util.List<?>) model.getAttribute("secondsList")).size());
    }

    @Test
    void theEditorOffersTheWeblogsCategoriesAndPlugins() throws Exception {
        org.apache.roller.weblogger.business.plugins.entry.WeblogEntryPlugin plugin =
                org.mockito.Mockito.mock(
                        org.apache.roller.weblogger.business.plugins.entry.WeblogEntryPlugin.class);
        when(weblogger.getPluginManager().getWeblogEntryPlugins(weblog))
                .thenReturn(java.util.Map.of("ConvertLineBreaks", plugin));

        controller.entryAddExecute(request, model, bean);

        assertEquals(java.util.List.of(category), model.getAttribute("categories"));
        assertEquals(1, ((java.util.List<?>) model.getAttribute("entryPlugins")).size(),
                "The plugin checkboxes come from this list; empty means the author cannot "
                        + "enable any formatting plugin");
    }

    @Test
    void aFailureListingCategoriesLeavesAnEmptyDropdownRatherThanBreakingTheEditor() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenThrow(new WebloggerException("database down"));

        controller.entryAddExecute(request, model, bean);

        assertTrue(((java.util.List<?>) model.getAttribute("categories")).isEmpty(),
                "A null here would break the JSP outright");
    }

    @Test
    void republishingAnAlreadyPublishedEntryDoesNotTouchTheAggregates() throws Exception {
        existingEntry(PubStatus.PUBLISHED);
        userMayPost = true;

        controller.entryEditPublish(request, model, bean);

        assertFalse(captureSavedEntry().getRefreshAggregates(),
                "Editing a live entry does not change the published count");
    }

    @Test
    void editingStaysOnTheEditorRatherThanRedirecting() throws Exception {
        existingEntry(PubStatus.DRAFT);

        assertEquals(".EntryEdit", controller.entryEditSaveDraft(request, model, bean),
                "Only the very first save redirects; subsequent saves stay on the form");
    }

    // --- status messages ---

    @Test
    void eachPublicationStateGetsItsOwnConfirmationMessage() throws Exception {
        assertEquals("weblogEdit.draftSaved", statusMessageAfterEditSave(PubStatus.DRAFT, false, null));
        assertEquals("weblogEdit.publishedEntry", statusMessageAfterEditSave(PubStatus.DRAFT, true, null));
        assertEquals("weblogEdit.submittedForReview",
                statusMessageAfterEditSave(PubStatus.DRAFT, true, Boolean.FALSE));
    }

    @Test
    void schedulingAnEntryConfirmsWithTheDateItWillGoLive() throws Exception {
        // The scheduled message is the only one that carries an argument; the
        // author needs to be told *when*, not just that it was scheduled.
        registerMessage("weblogEdit.scheduledEntry", "scheduled for {0}");
        existingEntry(PubStatus.DRAFT);
        userMayPost = true;
        setBeanPubTime(new Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000));

        controller.entryEditPublish(request, model, bean);

        assertTrue(messages(model).stream().anyMatch(m -> m.startsWith("scheduled for ")),
                "Expected a scheduled-for message carrying the date, got: " + messages(model));
    }

    // --- pinned to main ---

    @Test
    void onlyGlobalAdminsCanPinAnEntryToTheFrontPage() throws Exception {
        // pinnedToMain promotes an entry onto the site-wide front page, across
        // every blog. A weblog admin must not be able to grant it to themselves
        // just by posting the checkbox.
        userIsGlobalAdmin = false;
        bean.setPinnedToMain(true);

        controller.entryAddSaveDraft(request, model, bean);

        assertFalse(Boolean.TRUE.equals(captureSavedEntry().getPinnedToMain()),
                "A non-admin must not be able to pin an entry to the site front page");
    }

    @Test
    void globalAdminsCanPinAnEntryToTheFrontPage() throws Exception {
        userIsGlobalAdmin = true;
        bean.setPinnedToMain(true);

        controller.entryAddSaveDraft(request, model, bean);

        assertTrue(captureSavedEntry().getPinnedToMain());
    }

    // --- enclosures / mediacast ---

    @Test
    void anUnusableEnclosureUrlIsReportedWithoutLosingTheEntry() throws Exception {
        // A podcast URL the server cannot resolve must not cost the author
        // their post -- the entry still saves, with a warning.
        bean.setEnclosureURL("this is not a url");

        controller.entryAddSaveDraft(request, model, bean);

        assertTrue(messages(model).contains("weblogEdit.mediaCastUrlMalformed"),
                "Expected the malformed-url message, got: " + messages(model));
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(any());
    }

    @Test
    void clearingTheEnclosureUrlRemovesTheStoredPodcastAttributes() throws Exception {
        // All three attributes have to go. Leaving any behind would keep a
        // half-populated enclosure in the feed.
        existingEntry(PubStatus.DRAFT);
        bean.setEnclosureURL("");

        controller.entryEditSaveDraft(request, model, bean);

        verify(weblogger.getWeblogEntryManager())
                .removeWeblogEntryAttribute(org.mockito.ArgumentMatchers.eq("att_mediacast_url"), any());
        verify(weblogger.getWeblogEntryManager())
                .removeWeblogEntryAttribute(org.mockito.ArgumentMatchers.eq("att_mediacast_type"), any());
        verify(weblogger.getWeblogEntryManager())
                .removeWeblogEntryAttribute(org.mockito.ArgumentMatchers.eq("att_mediacast_length"), any());
    }

    @Test
    void aNewEntryWithNoEnclosureDoesNotTryToRemoveAttributesThatCannotExist() throws Exception {
        bean.setEnclosureURL(null);

        controller.entryAddSaveDraft(request, model, bean);

        verify(weblogger.getWeblogEntryManager(), never())
                .removeWeblogEntryAttribute(any(), any());
    }

    @Test
    void aFailureToClearTheEnclosureIsReportedRatherThanSwallowed() throws Exception {
        existingEntry(PubStatus.DRAFT);
        bean.setEnclosureURL("");
        doThrow(new WebloggerException("no such attribute"))
                .when(weblogger.getWeblogEntryManager())
                .removeWeblogEntryAttribute(org.mockito.ArgumentMatchers.eq("att_mediacast_url"), any());

        controller.entryEditSaveDraft(request, model, bean);

        assertTrue(messages(model).contains("weblogEdit.mediaCastErrorRemoving"),
                "Expected the removal failure to be surfaced, got: " + messages(model));
    }

    // --- error handling ---

    @Test
    void aFailedSaveReportsAnErrorAndStaysOnTheForm() throws Exception {
        doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).saveWeblogEntry(any());

        String view = controller.entryAddSaveDraft(request, model, bean);

        assertEquals(".EntryEdit", view, "A failed save must redisplay the form, not redirect away");
        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected an error to be reported, got: " + errors(model));
        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any(WeblogEntry.class));
    }

    @Test
    void aFailedAddClearsTheStatusSoTheFormDoesNotClaimTheEntryWasSaved() throws Exception {
        doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).saveWeblogEntry(any());

        controller.entryAddSaveDraft(request, model, bean);

        assertNull(bean.getStatus(),
                "After a failed add the form must not still show a publication status");
    }

    @Test
    void anEntryFiledUnderAnotherWeblogsCategoryIsRefused() throws Exception {
        // copyTo raises this; the controller has to turn it into an error
        // rather than a stack trace, and must not persist anything.
        WeblogCategory foreign = new WeblogCategory();
        foreign.setId("cat-foreign");
        foreign.setName("Theirs");
        org.apache.roller.weblogger.pojos.Weblog other =
                new org.apache.roller.weblogger.pojos.Weblog();
        other.setId("weblog-2");
        foreign.setWeblog(other);
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-foreign")).thenReturn(foreign);
        bean.setCategoryId("cat-foreign");

        controller.entryAddSaveDraft(request, model, bean);

        assertTrue(errors(model).contains("generic.error.check.logs"),
                "A cross-weblog category must be refused: " + errors(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    // --- SEO panel ---

    @Test
    void savingCarriesTheSeoFieldsOntoTheEntry() throws Exception {
        // The SEO card's inputs bind straight onto the bean; a field dropped in
        // copyTo would silently discard what the author typed.
        bean.setMetaTitle("A meta title");
        bean.setSearchDescription("A meta description");
        bean.setFeaturedImageId("mf-featured");
        bean.setOgImageId("mf-og");
        bean.setCanonicalUrl("https://example.com/the-original");
        bean.setNoindex(true);

        controller.entryAddSaveDraft(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals("A meta title", saved.getMetaTitle());
        assertEquals("A meta description", saved.getSearchDescription());
        assertEquals("mf-featured", saved.getFeaturedImageId());
        assertEquals("mf-og", saved.getOgImageId());
        assertEquals("https://example.com/the-original", saved.getCanonicalUrl());
        assertEquals(Boolean.TRUE, saved.getNoindex());
    }

    @Test
    void openingAnExistingEntryLoadsTheSeoFieldsIntoTheForm() throws Exception {
        WeblogEntry existing = existingEntry(PubStatus.PUBLISHED);
        existing.setMetaTitle("Stored meta title");
        existing.setSearchDescription("Stored description");
        existing.setFeaturedImageId("mf-featured");
        existing.setOgImageId("mf-og");
        existing.setCanonicalUrl("https://example.com/stored");
        existing.setNoindex(Boolean.TRUE);

        controller.entryEditExecute(request, model, bean);

        assertEquals("Stored meta title", bean.getMetaTitle());
        assertEquals("Stored description", bean.getSearchDescription());
        assertEquals("mf-featured", bean.getFeaturedImageId());
        assertEquals("mf-og", bean.getOgImageId());
        assertEquals("https://example.com/stored", bean.getCanonicalUrl());
        assertTrue(bean.getNoindex());
    }

    @Test
    void theEditorShowsThumbnailsForTheStoredSeoImages() throws Exception {
        WeblogEntry existing = existingEntry(PubStatus.PUBLISHED);
        existing.setFeaturedImageId("mf-featured");
        existing.setOgImageId("mf-og");
        givenMediaFile("mf-featured");
        givenMediaFile("mf-og");

        controller.entryEditExecute(request, model, bean);

        assertEquals("http://media/mf-featured?t=true",
                model.getAttribute("featuredImageThumbnailUrl"));
        assertEquals("http://media/mf-og?t=true", model.getAttribute("ogImageThumbnailUrl"));
    }

    @Test
    void aDanglingImageIdRendersNoPreviewRatherThanBreakingTheEditor() throws Exception {
        // The picked image can be deleted from the media library afterwards;
        // the editor must still open, just without a preview.
        WeblogEntry existing = existingEntry(PubStatus.PUBLISHED);
        existing.setFeaturedImageId("mf-deleted");
        existing.setOgImageId("mf-broken");
        when(weblogger.getMediaFileManager().getMediaFile("mf-deleted")).thenReturn(null);
        when(weblogger.getMediaFileManager().getMediaFile("mf-broken"))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.entryEditExecute(request, model, bean);

        assertEquals(".EntryEdit", view);
        assertNull(model.getAttribute("featuredImageThumbnailUrl"));
        assertNull(model.getAttribute("ogImageThumbnailUrl"));
    }

    @Test
    void anEntryWithoutSeoImagesAsksTheMediaTierForNothing() throws Exception {
        existingEntry(PubStatus.PUBLISHED);

        controller.entryEditExecute(request, model, bean);

        verify(weblogger.getMediaFileManager(), never()).getMediaFile(any());
        assertNull(model.getAttribute("featuredImageThumbnailUrl"));
        assertNull(model.getAttribute("ogImageThumbnailUrl"));
    }

    // --- helpers ---

    /** Stub a media file whose thumbnail resolves to {@code http://media/<id>?t=true}. */
    private void givenMediaFile(String id) throws WebloggerException {
        org.apache.roller.weblogger.pojos.MediaFile mediaFile =
                new org.apache.roller.weblogger.pojos.MediaFile();
        mediaFile.setId(id);
        mediaFile.setWeblog(weblog);
        when(weblogger.getMediaFileManager().getMediaFile(id)).thenReturn(mediaFile);
        when(weblogger.getUrlStrategy().getMediaFileThumbnailURL(weblog, id, true))
                .thenReturn("http://media/" + id + "?t=true");
    }

    // --- per-entry share links ---

    @org.junit.jupiter.api.Nested
    class ShareLinks {

        @org.junit.jupiter.api.BeforeEach
        void installPasswordEncoder() {
            if (org.apache.roller.weblogger.ui.core.RollerContext.getPasswordEncoder() == null) {
                org.apache.roller.weblogger.ui.core.RollerContext.setPasswordEncoder(
                        org.apache.roller.weblogger.ui.core.RollerContext.createPasswordEncoder());
            }
        }

        @Test
        void creatingAShareLinkStoresAHashedPasswordAndRedirectsBack() throws Exception {
            existingEntry(PubStatus.PUBLISHED);
            var redirectAttributes = newRedirectAttributes();

            String view = controller.entryEditCreateShareLink(
                    request, redirectAttributes, "entry-1", "entry-pw");

            assertEquals("redirect:/roller-ui/authoring/entryEdit.rol?weblog="
                    + WEBLOG_HANDLE + "&bean.id=entry-1", view);
            ArgumentCaptor<ShareLink> captor = ArgumentCaptor.forClass(ShareLink.class);
            verify(weblogger.getShareLinkManager()).createShareLink(captor.capture());
            ShareLink stored = captor.getValue();
            assertEquals(ShareLink.TYPE_ENTRY, stored.getTargetType());
            assertEquals("entry-1", stored.getTargetId());
            assertEquals(43, stored.getToken().length());
            assertNotNull(stored.getPasswordHash());
            assertTrue(stored.getPasswordHash().startsWith("{"),
                    "the value must be DelegatingPasswordEncoder output, not "
                            + "plaintext: " + stored.getPasswordHash());
            assertTrue(org.apache.roller.weblogger.ui.core.RollerContext.getPasswordEncoder()
                    .matches("entry-pw", stored.getPasswordHash()));
            assertTrue(flashMessages(redirectAttributes).contains("shareLink.created"));
        }

        @Test
        void anEntryFromAnotherWeblogCannotBeShared() throws Exception {
            WeblogEntry foreign = existingEntry(PubStatus.PUBLISHED);
            org.apache.roller.weblogger.pojos.Weblog other =
                    new org.apache.roller.weblogger.pojos.Weblog();
            other.setId("weblog-2");
            other.setHandle("otherblog");
            foreign.setWebsite(other);

            String view = controller.entryEditCreateShareLink(
                    request, newRedirectAttributes(), "entry-1", null);

            assertEquals("redirect:/roller-ui/menu.rol", view,
                    "an entryId from another weblog must bounce, exactly like "
                            + "an unknown one");
            verify(weblogger.getShareLinkManager(), never()).createShareLink(any());
        }

        @Test
        void revokingRemovesTheLinkAndRedirectsBack() throws Exception {
            existingEntry(PubStatus.PUBLISHED);
            ShareLink link = new ShareLink();
            link.setTargetType(ShareLink.TYPE_ENTRY);
            link.setTargetId("entry-1");
            link.setToken("entry-share-token");
            when(weblogger.getShareLinkManager()
                    .getShareLinkForTarget(ShareLink.TYPE_ENTRY, "entry-1")).thenReturn(link);
            var redirectAttributes = newRedirectAttributes();

            String view = controller.entryEditRevokeShareLink(
                    request, redirectAttributes, "entry-1");

            assertEquals("redirect:/roller-ui/authoring/entryEdit.rol?weblog="
                    + WEBLOG_HANDLE + "&bean.id=entry-1", view);
            verify(weblogger.getShareLinkManager()).removeShareLink(link);
            assertTrue(flashMessages(redirectAttributes).contains("shareLink.revoked"));
        }

        @Test
        void revokingWithNoLinkReportsAnError() throws Exception {
            existingEntry(PubStatus.PUBLISHED);
            var redirectAttributes = newRedirectAttributes();

            String view = controller.entryEditRevokeShareLink(
                    request, redirectAttributes, "entry-1");

            assertEquals("redirect:/roller-ui/authoring/entryEdit.rol?weblog="
                    + WEBLOG_HANDLE + "&bean.id=entry-1", view);
            verify(weblogger.getShareLinkManager(), never()).removeShareLink(any());
            assertTrue(flashErrors(redirectAttributes).contains("shareLink.error"));
        }

        @Test
        void anEntryFromAnotherWeblogCannotBeRevoked() throws Exception {
            WeblogEntry foreign = existingEntry(PubStatus.PUBLISHED);
            org.apache.roller.weblogger.pojos.Weblog other =
                    new org.apache.roller.weblogger.pojos.Weblog();
            other.setId("weblog-2");
            other.setHandle("otherblog");
            foreign.setWebsite(other);

            String view = controller.entryEditRevokeShareLink(
                    request, newRedirectAttributes(), "entry-1");

            assertEquals("redirect:/roller-ui/menu.rol", view);
            verify(weblogger.getShareLinkManager(), never()).removeShareLink(any());
        }

        @Test
        void theEditFormExposesTheShareUrl() throws Exception {
            org.apache.roller.weblogger.config.WebloggerRuntimeConfig
                    .setAbsoluteContextURL("http://localhost:8080/roller");
            existingEntry(PubStatus.PUBLISHED);
            ShareLink link = new ShareLink();
            link.setTargetType(ShareLink.TYPE_ENTRY);
            link.setTargetId("entry-1");
            link.setToken("entry-view-token");
            when(weblogger.getShareLinkManager()
                    .getShareLinkForTarget(ShareLink.TYPE_ENTRY, "entry-1")).thenReturn(link);

            controller.entryEditExecute(request, model, bean);

            assertEquals(link, model.getAttribute("entryShareLink"));
            assertEquals("http://localhost:8080/roller/share/entry-view-token",
                    model.getAttribute("entryShareURL"),
                    "the JSP copies this URL verbatim; it must be absolute");
        }
    }

    private WeblogEntry existingEntry(PubStatus status) throws WebloggerException {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setTitle("Stored title");
        entry.setWebsite(weblog);
        entry.setCategory(category);
        entry.setStatus(status);
        entry.setCreatorUserName(USER_NAME);
        entry.setCommentDays(0);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);
        bean.setId("entry-1");
        return entry;
    }

    private WeblogEntry captureSavedEntry() throws WebloggerException {
        ArgumentCaptor<WeblogEntry> captor = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getWeblogEntryManager(), atLeastOnce()).saveWeblogEntry(captor.capture());
        return captor.getValue();
    }

    private void setBeanPubTime(Date when) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("MM/dd/yy", Locale.US);
        dateFormat.setTimeZone(NEW_YORK);
        java.util.Calendar calendar = java.util.Calendar.getInstance(NEW_YORK, Locale.US);
        calendar.setTime(when);
        bean.setDateString(dateFormat.format(when));
        bean.setHours(calendar.get(java.util.Calendar.HOUR_OF_DAY));
        bean.setMinutes(calendar.get(java.util.Calendar.MINUTE));
        bean.setSeconds(calendar.get(java.util.Calendar.SECOND));
    }

    /**
     * Drive an edit-save and return the single status message it produced.
     *
     * @param publish     whether to take the publish action rather than save-draft
     * @param mayPost     null to leave POST permission as-is, FALSE to withhold it
     */
    private String statusMessageAfterEditSave(PubStatus startingStatus, boolean publish,
                                              Boolean mayPost) throws Exception {
        setUp();
        existingEntry(startingStatus);
        if (mayPost != null) {
            userMayPost = mayPost;
        }
        if (publish) {
            controller.entryEditPublish(request, model, bean);
        } else {
            controller.entryEditSaveDraft(request, model, bean);
        }
        assertEquals(1, messages(model).size(),
                "Expected exactly one status message, got: " + messages(model));
        return messages(model).get(0);
    }
}

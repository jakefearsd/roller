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

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.CommentSearchCriteria;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntryBean}, the form model behind the entry editor.
 *
 * <p>Two things here are worth pinning down. First {@code getPubTime}, which
 * turns a hand-typed date plus three integer selectors into the instant an
 * entry goes live: get the timezone handling wrong and posts publish hours off,
 * or a scheduled post fires immediately. Second {@code copyTo}, which is the
 * only place that checks a submitted category actually belongs to the weblog
 * being edited — without it, a crafted form could file an entry under another
 * blog's category.
 */
class EntryBeanTest extends EditorControllerTestSupport {

    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    private EntryBean bean;

    @BeforeEach
    void setUpBean() {
        bean = new EntryBean();
    }

    // --- getPubTime ---

    @Test
    void pubTimeCombinesTheTypedDateWithTheHourMinuteSecondSelectors() {
        bean.setDateString("3/15/24");
        bean.setHours(14);
        bean.setMinutes(30);
        bean.setSeconds(45);

        Timestamp pubTime = bean.getPubTime(Locale.US, NEW_YORK);

        assertEquals("2024-03-15 14:30:45", formatIn(pubTime, NEW_YORK),
                "The selectors must be applied as wall-clock time in the weblog's timezone");
    }

    @Test
    void pubTimeIsInterpretedInTheWeblogsTimezoneNotTheServersa() {
        bean.setDateString("3/15/24");
        bean.setHours(12);

        Timestamp inNewYork = bean.getPubTime(Locale.US, NEW_YORK);
        Timestamp inTokyo = bean.getPubTime(Locale.US, TimeZone.getTimeZone("Asia/Tokyo"));

        assertNotEquals(inNewYork, inTokyo,
                "Noon in New York and noon in Tokyo are different instants; if these come out "
                        + "equal the timezone is being ignored and scheduled posts will fire at "
                        + "the wrong time for every blog not in the server's zone");
    }

    @Test
    void pubTimeAcceptsSingleDigitMonthAndDay() {
        // The pattern is "M/d/yy" precisely so users need not type leading
        // zeroes; "03/05/24" must still work too.
        bean.setDateString("3/5/24");
        Timestamp terse = bean.getPubTime(Locale.US, NEW_YORK);

        bean.setDateString("03/05/24");
        Timestamp padded = bean.getPubTime(Locale.US, NEW_YORK);

        assertEquals(terse, padded, "Leading zeroes must be optional, not significant");
        assertEquals("2024-03-05 00:00:00", formatIn(terse, NEW_YORK));
    }

    @Test
    void pubTimeIgnoresTheRequestLocale() {
        // Pins the behaviour the TODO in getPubTime describes: the date is
        // parsed with a fixed US pattern regardless of the user's locale,
        // because the calendar widget that produces it only speaks US format.
        // If this ever starts failing, the widget and the parser have been made
        // locale-aware together -- update the TODO and this test as a pair.
        bean.setDateString("3/15/24");
        bean.setHours(9);

        Timestamp asUs = bean.getPubTime(Locale.US, NEW_YORK);
        Timestamp asFrench = bean.getPubTime(Locale.FRANCE, NEW_YORK);
        Timestamp asThai = bean.getPubTime(Locale.forLanguageTag("th-TH-u-ca-buddhist"), NEW_YORK);

        assertEquals(asUs, asFrench, "Locale must not change how the date string is read");
        assertEquals(asUs, asThai,
                "A non-Gregorian calendar locale must not shift the year either");
    }

    @Test
    void pubTimeIsNullWhenNoDateWasTyped() {
        // A null pubTime is meaningful: EntryEditController reads it as
        // "publish now" rather than "schedule".
        assertNull(bean.getPubTime(Locale.US, NEW_YORK), "No date string means no pub time");

        bean.setDateString("");
        assertNull(bean.getPubTime(Locale.US, NEW_YORK), "An empty date string means no pub time");
    }

    @Test
    void pubTimeIsNullWhenTheDateCannotBeParsed() {
        bean.setDateString("not-a-date");

        assertNull(bean.getPubTime(Locale.US, NEW_YORK),
                "Unparseable input must degrade to 'publish now', not throw out of the controller");
    }

    // --- status predicates ---

    @Test
    void statusPredicatesMatchOnlyTheirOwnStatus() {
        bean.setStatus(PubStatus.DRAFT.name());
        assertTrue(bean.isDraft());
        assertFalse(bean.isPending() || bean.isPublished() || bean.isScheduled());

        bean.setStatus(PubStatus.PENDING.name());
        assertTrue(bean.isPending());
        assertFalse(bean.isDraft() || bean.isPublished() || bean.isScheduled());

        bean.setStatus(PubStatus.PUBLISHED.name());
        assertTrue(bean.isPublished());
        assertFalse(bean.isDraft() || bean.isPending() || bean.isScheduled());

        bean.setStatus(PubStatus.SCHEDULED.name());
        assertTrue(bean.isScheduled());
        assertFalse(bean.isDraft() || bean.isPending() || bean.isPublished());
    }

    @Test
    void statusPredicatesAreAllFalseWhenNoStatusIsSet() {
        assertFalse(bean.isDraft() || bean.isPending() || bean.isPublished() || bean.isScheduled(),
                "An unset status must not accidentally read as one of the real statuses");
    }

    // --- comment days ---

    @Test
    void settingCommentDaysToMinusOneTurnsCommentsOff() {
        // -1 is the "no comments" option in the dropdown, so it has to imply
        // allowComments=false; leaving the flag on would let the entry keep
        // accepting comments while the UI says it does not.
        bean.setAllowComments(true);
        bean.setCommentDays(-1);

        assertFalse(bean.getAllowComments(),
                "Choosing 'no comments' must clear the allowComments flag");
        assertEquals(-1, bean.getCommentDays());
    }

    @Test
    void settingAnyOtherCommentDaysLeavesTheAllowCommentsFlagAlone() {
        bean.setAllowComments(true);
        bean.setCommentDays(30);

        assertTrue(bean.getAllowComments(), "Only -1 means 'no comments'");
        assertEquals(30, bean.getCommentDays());
    }

    // --- copyTo ---

    @Test
    void copyToEscapesTheTitleButNotTheBody() throws Exception {
        // Titles are rendered as text in a great many places, so they are
        // escaped on the way in. Entry bodies are deliberately raw HTML.
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setTitle("Tom & Jerry <b>rule</b>");
        bean.setText("<p>Real markup stays</p>");

        bean.copyTo(entry);

        assertEquals("Tom &amp; Jerry &lt;b&gt;rule&lt;/b&gt;", entry.getTitle());
        assertEquals("<p>Real markup stays</p>", entry.getText());
    }

    @Test
    void copyToRejectsACategoryBelongingToAnotherWeblog() throws Exception {
        // This is the authorization check on the category dropdown. The form
        // posts a bare category id, so without it any blog could file entries
        // into any other blog's category.
        WeblogEntry entry = entryInCategory("cat-1");
        WeblogCategory foreign = new WeblogCategory();
        foreign.setId("cat-9");
        foreign.setName("Someone else's");
        org.apache.roller.weblogger.pojos.Weblog otherWeblog =
                new org.apache.roller.weblogger.pojos.Weblog();
        otherWeblog.setId("weblog-2");
        otherWeblog.setHandle("otherblog");
        foreign.setWeblog(otherWeblog);
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-9")).thenReturn(foreign);

        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-9");

        WebloggerException ex = assertThrows(WebloggerException.class, () -> bean.copyTo(entry));
        assertTrue(ex.getMessage().contains("Illegal category"),
                "Expected the cross-weblog category to be refused, got: " + ex.getMessage());
    }

    @Test
    void copyToRejectsACategoryIdThatDoesNotResolve() throws Exception {
        WeblogEntry entry = entryInCategory("cat-1");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("nope")).thenReturn(null);

        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("nope");

        WebloggerException ex = assertThrows(WebloggerException.class, () -> bean.copyTo(entry));
        assertTrue(ex.getMessage().contains("could not be found"),
                "Expected a not-found complaint, got: " + ex.getMessage());
    }

    @Test
    void copyToRefusesToSaveAnEntryWithNoCategory() throws Exception {
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId(null);

        WebloggerException ex = assertThrows(WebloggerException.class, () -> bean.copyTo(entry));
        assertTrue(ex.getMessage().contains("No category specified"),
                "Expected a missing-category complaint, got: " + ex.getMessage());
    }

    @Test
    void copyToStripsPunctuationFromTags() throws Exception {
        // Tags become URL path segments, so anything non-alphanumeric is
        // flattened to a space (and thereby splits the tag) before storage.
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setTagsAsString("hello,world foo/bar");

        bean.copyTo(entry);

        assertEquals("bar foo hello world", entry.getTagsAsString(),
                "Punctuation must split tags rather than survive into the tag name");
    }

    @Test
    void copyToTurnsNullTagsIntoNoTagsRatherThanThrowing() throws Exception {
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setTagsAsString(null);

        bean.copyTo(entry);

        assertEquals("", entry.getTagsAsString());
    }

    @Test
    void copyToJoinsSelectedPluginsIntoTheStoredCsv() throws Exception {
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setPlugins(new String[]{"ConvertLineBreaks", "TextileFormatter"});

        bean.copyTo(entry);

        assertEquals("ConvertLineBreaks,TextileFormatter", entry.getPlugins());
    }

    @Test
    void copyToCarriesCommentSettingsAcross() throws Exception {
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setAllowComments(true);
        bean.setCommentDays(14);
        bean.setRightToLeft(true);
        bean.setSummary("A summary");
        bean.setSearchDescription("For search engines");
        bean.setLocale("fr_FR");

        bean.copyTo(entry);

        assertTrue(entry.getAllowComments());
        assertEquals(14, entry.getCommentDays());
        assertTrue(entry.getRightToLeft());
        assertEquals("A summary", entry.getSummary());
        assertEquals("For search engines", entry.getSearchDescription());
        assertEquals("fr_FR", entry.getLocale());
    }

    @Test
    void copyToDoesNotTouchPubTimeOrPinnedToMain() throws Exception {
        // Both are set by EntryEditController, which applies rules copyTo has
        // no way to know about (scheduling, and the global-admin gate on
        // pinning). If copyTo started writing them it would silently undo those
        // decisions.
        WeblogEntry entry = entryInCategory("cat-1");
        Timestamp existingPubTime = new Timestamp(1_700_000_000_000L);
        entry.setPubTime(existingPubTime);
        entry.setPinnedToMain(Boolean.TRUE);

        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setPinnedToMain(false);
        bean.setDateString("1/1/20");

        bean.copyTo(entry);

        assertEquals(existingPubTime, entry.getPubTime(), "copyTo must leave pubTime alone");
        assertTrue(entry.getPinnedToMain(), "copyTo must leave pinnedToMain alone");
    }

    // --- copyFrom ---

    @Test
    void copyFromUnescapesTheTitleForRedisplayInTheEditor() throws Exception {
        // The stored title is escaped; the editor's text input must show the
        // characters the author actually typed, or every save would re-escape
        // and "&" would creep towards "&amp;amp;".
        WeblogEntry entry = storedEntry();
        entry.setTitle("Tom &amp; Jerry");

        bean.copyFrom(entry, Locale.US);

        assertEquals("Tom & Jerry", bean.getTitle());
    }

    @Test
    void copyFromSplitsTheStoredPluginCsvBackIntoAnArray() throws Exception {
        WeblogEntry entry = storedEntry();
        entry.setPlugins("ConvertLineBreaks,TextileFormatter");

        bean.copyFrom(entry, Locale.US);

        assertEquals(2, bean.getPlugins().length);
        assertEquals("ConvertLineBreaks", bean.getPlugins()[0]);
        assertEquals("TextileFormatter", bean.getPlugins()[1]);
    }

    @Test
    void copyFromSplitsAStoredPubTimeIntoTheEditorsDateAndTimeFields() throws Exception {
        WeblogEntry entry = storedEntry();
        // 2024-03-15 14:30:45 in America/New_York.
        entry.setPubTime(Timestamp.valueOf("2024-03-15 14:30:45"));
        // The weblog's timezone is what the editor displays in.
        weblog.setTimeZone("America/New_York");
        SimpleDateFormat utcInput = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        utcInput.setTimeZone(NEW_YORK);
        entry.setPubTime(new Timestamp(utcInput.parse("2024-03-15 14:30:45").getTime()));

        bean.copyFrom(entry, Locale.US);

        assertEquals("03/15/24", bean.getDateString());
        assertEquals(14, bean.getHours());
        assertEquals(30, bean.getMinutes());
        assertEquals(45, bean.getSeconds());
    }

    @Test
    void copyFromLeavesTheDateBlankForAnEntryThatWasNeverPublished() throws Exception {
        WeblogEntry entry = storedEntry();
        entry.setPubTime(null);

        bean.copyFrom(entry, Locale.US);

        assertNull(bean.getDateString(),
                "A draft with no pub time must not be given a fabricated date");
    }

    @Test
    void copyFromRoundTripsThroughGetPubTime() throws Exception {
        // The editor's whole date story is copyFrom -> user edits -> getPubTime.
        // If the two disagree on format or timezone, merely opening and saving
        // an entry would move its publication time.
        WeblogEntry entry = storedEntry();
        SimpleDateFormat inZone = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        inZone.setTimeZone(NEW_YORK);
        Timestamp original = new Timestamp(inZone.parse("2024-07-04 09:08:07").getTime());
        entry.setPubTime(original);

        bean.copyFrom(entry, Locale.US);
        Timestamp reparsed = bean.getPubTime(Locale.US, NEW_YORK);

        assertEquals(original, reparsed,
                "Opening an entry and saving it unchanged must not move its publication time");
    }

    @Test
    void copyFromCarriesEveryFieldTheEditorRedisplays() throws Exception {
        // Exhaustive on purpose. copyFrom is the only thing that populates the
        // edit form, and a field it silently fails to copy comes back blank in
        // the textarea -- which the very next save then persists, destroying
        // the stored value. A round-trip test that checks only a few fields
        // would not notice.
        WeblogEntry entry = storedEntry();
        entry.setTitle("Stored title");
        entry.setLocale("de_DE");
        entry.setStatus(PubStatus.SCHEDULED);
        entry.setSummary("The summary");
        entry.setText("The body");
        entry.setSearchDescription("The search description");
        entry.setTagsAsString("alpha beta");
        entry.setAllowComments(Boolean.FALSE);
        entry.setCommentDays(30);
        entry.setRightToLeft(Boolean.TRUE);
        entry.setPinnedToMain(Boolean.TRUE);

        bean.copyFrom(entry, Locale.US);

        assertEquals("entry-1", bean.getId());
        assertEquals("Stored title", bean.getTitle());
        assertEquals("de_DE", bean.getLocale());
        assertEquals(PubStatus.SCHEDULED.name(), bean.getStatus());
        assertEquals("The summary", bean.getSummary());
        assertEquals("The body", bean.getText());
        assertEquals("The search description", bean.getSearchDescription());
        assertEquals("cat-1", bean.getCategoryId());
        assertEquals("alpha beta", bean.getTagsAsString());
        assertFalse(bean.getAllowComments());
        assertEquals(30, bean.getCommentDays());
        assertTrue(bean.getRightToLeft());
        assertTrue(bean.getPinnedToMain());
    }

    @Test
    void copyToCarriesTheNegativeCaseOfEveryFlagToo() throws Exception {
        // The flags all default to true on a fresh WeblogEntry, so a copyTo
        // that dropped them would still look correct in a test that only ever
        // sets them to true.
        WeblogEntry entry = entryInCategory("cat-1");
        entry.setAllowComments(Boolean.TRUE);
        entry.setRightToLeft(Boolean.TRUE);
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setAllowComments(false);
        bean.setRightToLeft(false);

        bean.copyTo(entry);

        assertFalse(entry.getAllowComments());
        assertFalse(entry.getRightToLeft());
    }

    @Test
    void copyToFilesTheEntryUnderTheSelectedCategory() throws Exception {
        // The category drives the entry's URL and its feed membership.
        WeblogEntry entry = entryInCategory("cat-1");
        WeblogCategory replacement = new WeblogCategory();
        replacement.setId("cat-2");
        replacement.setName("Food");
        replacement.setWeblog(weblog);
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-2")).thenReturn(replacement);

        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-2");
        bean.copyTo(entry);

        assertEquals(replacement, entry.getCategory());
    }

    @Test
    void flagsDefaultToFalseOnAFreshForm() {
        // The "add entry" form starts from a new bean; a flag defaulting to
        // true would silently pin every new post to the site front page.
        EntryBean fresh = new EntryBean();
        assertFalse(fresh.getPinnedToMain());
        assertFalse(fresh.getRightToLeft());
        assertFalse(fresh.getAllowComments());
    }

    @Test
    void copyFromPicksUpTheEnclosureUrlFromTheEntryAttributes() throws Exception {
        WeblogEntry entry = storedEntry();
        entry.putEntryAttribute("att_mediacast_url", "https://example.com/podcast.mp3");
        entry.putEntryAttribute("att_mediacast_type", "audio/mpeg");

        bean.copyFrom(entry, Locale.US);

        assertEquals("https://example.com/podcast.mp3", bean.getEnclosureURL(),
                "The podcast enclosure must be read back out of the attribute set, and only "
                        + "the url attribute -- not the type or length -- is the enclosure");
    }

    @Test
    void copyFromCountsEveryCommentIncludingSpamAndPending() throws Exception {
        // The editor shows this count next to a link to comment moderation, so
        // it must not filter by approval status -- a pending comment is exactly
        // the one the author needs to be told about.
        WeblogEntry entry = storedEntry();
        when(weblogger.getWeblogEntryManager().getComments(
                ArgumentMatchers.any(CommentSearchCriteria.class)))
                .thenReturn(java.util.List.of(
                        new org.apache.roller.weblogger.pojos.WeblogEntryComment(),
                        new org.apache.roller.weblogger.pojos.WeblogEntryComment(),
                        new org.apache.roller.weblogger.pojos.WeblogEntryComment()));

        bean.copyFrom(entry, Locale.US);

        assertEquals(3, bean.getCommentCount());
    }

    @Test
    void copyFromReportsZeroCommentsWhenTheLookupFails() throws Exception {
        WeblogEntry entry = storedEntry();
        when(weblogger.getWeblogEntryManager().getComments(
                ArgumentMatchers.any(CommentSearchCriteria.class)))
                .thenThrow(new WebloggerException("database down"));

        bean.copyFrom(entry, Locale.US);

        assertEquals(0, bean.getCommentCount(),
                "A failed comment count must not stop the editor from opening");
    }

    private WeblogEntry entryInCategory(String categoryId) throws WebloggerException {
        WeblogCategory category = new WeblogCategory();
        category.setId(categoryId);
        category.setName("Travel");
        category.setWeblog(weblog);
        when(weblogger.getWeblogEntryManager().getWeblogCategory(categoryId)).thenReturn(category);

        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setCategory(category);
        return entry;
    }

    private WeblogEntry storedEntry() throws WebloggerException {
        WeblogEntry entry = entryInCategory("cat-1");
        entry.setId("entry-1");
        entry.setTitle("A title");
        entry.setStatus(PubStatus.PUBLISHED);
        entry.setLocale("en_US");
        entry.setCommentDays(0);
        when(weblogger.getWeblogEntryManager().getComments(
                ArgumentMatchers.any(CommentSearchCriteria.class)))
                .thenReturn(Collections.emptyList());
        return entry;
    }

    private static String formatIn(Timestamp timestamp, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(zone);
        return format.format(timestamp);
    }
}

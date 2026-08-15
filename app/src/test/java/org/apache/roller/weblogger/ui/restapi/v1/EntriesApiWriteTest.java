package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.Test;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.*;

class EntriesApiWriteTest {

    private static Weblog weblogInZone(String zoneId) {
        Weblog weblog = new Weblog();
        weblog.setTimeZone(zoneId);
        return weblog;
    }

    /**
     * The title must arrive escaped exactly once. Themes emit $entry.title
     * bare, so an unescaped store is stored XSS and a double-escaped store
     * renders "&amp;amp;".
     */
    @Test
    void applyingAWriteEscapesTheTitleExactlyOnce() {
        WeblogEntry entry = new WeblogEntry();
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                "Cats & Dogs", null, null, null, null, null, null,
                null, null, null, null, null, null);

        EntryDtos.applyWrite(entry, write, weblogInZone("UTC"));

        assertEquals("Cats &amp; Dogs", entry.getTitle());
    }

    /** A field absent from a PATCH body must not clear the stored value. */
    @Test
    void anAbsentFieldIsLeftAlone() {
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle("kept");
        entry.setText("also kept");
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, "new body", null, null, null, null,
                null, null, null, null, null, null);

        EntryDtos.applyWrite(entry, write, weblogInZone("UTC"));

        assertEquals("kept", entry.getTitle());
        assertEquals("new body", entry.getText());
    }

    /** pubTime is read in the weblog's zone, not the server's. */
    @Test
    void pubTimeIsParsedInTheWeblogsZone() {
        WeblogEntry utcEntry = new WeblogEntry();
        WeblogEntry tokyoEntry = new WeblogEntry();
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, "2026-03-01T09:30", null,
                null, null, null, null, null, null);

        EntryDtos.applyWrite(utcEntry, write, weblogInZone("UTC"));
        EntryDtos.applyWrite(tokyoEntry, write, weblogInZone("Asia/Tokyo"));

        assertNotEquals(utcEntry.getPubTime().getTime(), tokyoEntry.getPubTime().getTime());
    }

    @Test
    void aMistypedPubTimeIsRejectedRatherThanPublishingNow() {
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, "yesterday-ish", null,
                null, null, null, null, null, null);

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC")));
    }

    /**
     * Whole-branch review, Must Fix 1: weblogentry.title is varchar(255) NOT
     * NULL and the guard must run AFTER escapeTitle, not before -- a 200
     * -character title made entirely of '&' escapes to 1000 stored
     * characters (each '&' becomes "&amp;"), so a raw-input length check
     * would pass this exact case and still 500 on save. 200 raw characters
     * clears any pre-escape check but overflows the column once escaped.
     */
    @Test
    void aTitleThatOverflowsTheColumnOnlyAfterEscapingIsRejected() {
        String title = "&".repeat(200);
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                title, null, null, null, null, null, null,
                null, null, null, null, null, null);

        org.apache.roller.weblogger.ui.restapi.ApiException ex = assertThrows(
                org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC")));
        assertEquals(400, ex.getStatus());
    }

    /** A 255-character raw title that stays 255 characters once escaped must be accepted. */
    @Test
    void aTitleAtExactlyTheColumnLimitAfterEscapingIsAccepted() {
        String title = "a".repeat(255);
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                title, null, null, null, null, null, null,
                null, null, null, null, null, null);

        WeblogEntry entry = new WeblogEntry();
        EntryDtos.applyWrite(entry, write, weblogInZone("UTC"));
        assertEquals(255, entry.getTitle().length());
    }

    /**
     * metaTitle/searchDescription/canonicalUrl are all weblogentry
     * varchar(255) columns (V006__media_metadata_and_entry_seo.sql) with no
     * escaping step -- unlike title, the raw input length IS the stored
     * length, so a simple over-limit value is enough to prove the guard.
     */
    @Test
    void aMetaTitleLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, null, null,
                "x".repeat(256), null, null, null, null, null);

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC")));
    }

    @Test
    void aSearchDescriptionLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, null, null,
                null, "x".repeat(256), null, null, null, null);

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC")));
    }

    @Test
    void aCanonicalUrlLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, null, null,
                null, null, "x".repeat(256), null, null, null);

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC")));
    }

    /** featuredImageId/ogImageId store a roller_mediafile id -- varchar(48), the shortest column here. */
    @Test
    void aFeaturedImageIdLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, null, null,
                null, null, null, null, "x".repeat(49), null);

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC")));
    }

    @Test
    void anOgImageIdLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, null, null,
                null, null, null, null, null, "x".repeat(49));

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC")));
    }
}

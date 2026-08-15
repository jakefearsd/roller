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
}

package org.apache.roller.weblogger.business.shortcodes;

import java.util.Map;

import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [contact] emits a placeholder div, never a form: the sanitizer strips
 * form elements from authored content by design, and #showAudienceAssets
 * builds the real form client-side. Same pattern as [map] and [video].
 */
class ContactShortcodeTest {

    private final ContactShortcode shortcode = new ContactShortcode();

    private static ShortcodeContext context(String handle) {
        Weblog weblog = handle == null ? null : new Weblog();
        if (weblog != null) {
            weblog.setHandle(handle);
        }
        Weblog finalWeblog = weblog;
        return new ShortcodeContext() {
            @Override public Weblog getWeblog() { return finalWeblog; }
            @Override public String getSlug() { return "contact"; }
            @Override public String getRawText() { return "[contact]"; }
        };
    }

    @Test
    void emitsAPlaceholderCarryingTheWeblogHandle() {
        String html = shortcode.render(Map.of(), null, context("travelblog"));

        assertTrue(html.contains("class=\"contact-form-slot\""), html);
        assertTrue(html.contains("data-weblog=\"travelblog\""), html);
        assertFalse(html.contains("<form"), "the macro injects the form, not the shortcode");
        assertFalse(html.contains("<input"), html);
    }

    @Test
    void withoutAWeblogItLeavesTheAuthorsTextVisible() {
        assertNull(shortcode.render(Map.of(), null, context(null)));
    }

    @Test
    void theCardIsDiscoverable() {
        ShortcodeCard card = shortcode.getCard();
        assertEquals("contact", card.name());
        assertTrue(card.snippet().startsWith("[contact"), card.snippet());
        assertFalse(card.snippet().contains("<"));
    }

    @Test
    void isRegisteredInTheDefaultExpander() {
        assertTrue(ShortcodeExpander.defaultExpander().cards().stream()
                .anyMatch(c -> "contact".equals(c.name())));
    }
}

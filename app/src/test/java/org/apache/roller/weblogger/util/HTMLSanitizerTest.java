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

package org.apache.roller.weblogger.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the HTML sanitizer that guards weblog and comment content when
 * {@code weblogAdminsUntrusted} is on.
 *
 * <p>This class is a security control: everything it fails to strip ends up in
 * a rendered page. The tests below are therefore written as attack cases --
 * script tags, event handlers, javascript: URLs, CSS expressions -- plus the
 * structural cases (unbalanced tags, orphaned table rows) where a lenient
 * sanitizer would emit markup that breaks the page around it.
 */
public class HTMLSanitizerTest {

    @Nested
    class Attacks {

        @Test
        public void dropsScriptTagsAndReportsTheDocumentAsUnsafe() {
            assertEquals("alert(1)", HTMLSanitizer.sanitize("<script>alert(1)</script>"),
                    "A <script> element survived sanitizing -- anything stored through this "
                            + "path is now executable in a reader's browser.");
            assertFalse(HTMLSanitizer.isSanitized("<script>alert(1)</script>"),
                    "isSanitized() must report false so the editor can refuse the input; "
                            + "returning true silently accepts script.");
        }

        @Test
        public void dropsTheOtherExecutableElements() {
            // Each of these can load or run code just like <script> can.
            for (String tag : new String[]{"object", "embed", "link", "style", "form", "input"}) {
                String html = "<" + tag + ">x</" + tag + ">";
                assertFalse(HTMLSanitizer.isSanitized(html),
                        "<" + tag + "> is on the forbidden list but was accepted as valid");
                assertFalse(HTMLSanitizer.sanitize(html).contains("<" + tag),
                        "<" + tag + "> survived sanitizing: " + HTMLSanitizer.sanitize(html));
            }
        }

        @Test
        public void stripsInlineEventHandlers() {
            // onclick/onerror/onload are the simplest XSS vector that survives
            // a naive "tag whitelist only" sanitizer.
            assertEquals("<p>hi</p>", HTMLSanitizer.sanitize("<p onclick=\"evil()\">hi</p>"));
            assertEquals("<p>hi</p>", HTMLSanitizer.sanitize("<p ONERROR=\"evil()\">hi</p>"));
        }

        @Test
        public void dropsAnchorsWhoseHrefIsNotHttp() {
            // javascript: URLs are the reason href is validated rather than
            // merely encoded. With no usable href the whole anchor goes.
            assertEquals("x", HTMLSanitizer.sanitize("<a href=\"javascript:evil()\">x</a>"));
            assertEquals("x", HTMLSanitizer.sanitize("<a href=\"data:text/html;base64,xx\">x</a>"));
        }

        @Test
        public void dropsImagesWhoseSourceIsNotHttp() {
            assertEquals("", HTMLSanitizer.sanitize("<img src=\"javascript:evil()\">"));
        }

        @Test
        public void stripsCssExpressionsButKeepsTheRestOfTheStyle() {
            // IE-era expression()/eval() in CSS executes script; only the
            // offending declaration is dropped, the element survives.
            assertEquals("<p style=\"color:red;\">x</p>",
                    HTMLSanitizer.sanitize("<p style=\"color:red;width:expression(alert(1));\">x</p>"));
        }

        @Test
        public void stripsCssUrlsThatArePointedSomewhereOtherThanHttp() {
            assertEquals("<p style=\"\">x</p>",
                    HTMLSanitizer.sanitize("<p style=\"background:url('javascript:x');\">x</p>"));
        }

        @Test
        public void stripsCssUrlsWhoseValueContainsItsOwnParenthesis() {
            // Regression guard: the url-extraction pattern used to stop at the
            // first ')', so "url('javascript:evil()')" matched nothing at all
            // and the declaration was copied through without ever being
            // validated. A call is the payload, so this is the shape an
            // attacker actually writes.
            assertEquals("<p style=\"\">x</p>",
                    HTMLSanitizer.sanitize("<p style=\"background:url('javascript:evil()');\">x</p>"));
        }

        @Test
        public void keepsCssUrlsThatPointAtHttp() {
            assertEquals("<p style=\"background:url(&#39;http://e.com/a.png&#39;);\">x</p>",
                    HTMLSanitizer.sanitize("<p style=\"background:url('http://e.com/a.png');\">x</p>"));
        }

        @Test
        public void dropsUnknownTagsButKeepsTheTextInsideThem() {
            // An unknown tag is not markup we vouch for. It is left out of the
            // rendered html and recorded as invalid so the editor can warn;
            // the text it wrapped is still shown.
            HTMLSanitizer.SanitizeResult result = HTMLSanitizer.sanitizer("<blink>x</blink>");
            assertEquals("x", result.html);
            assertFalse(result.isValid,
                    "An unknown tag has to mark the document invalid, otherwise the editor "
                            + "reports content as clean while silently changing it.");
            assertTrue(result.invalidTags.contains("<blink>"),
                    "The rejected tag must be listed so it can be reported: " + result.invalidTags);
        }
    }

    @Nested
    class WellFormedContent {

        @Test
        public void leavesWhitelistedMarkupAlone() {
            assertEquals("<b>hi</b>", HTMLSanitizer.sanitize("<b>hi</b>"));
            assertTrue(HTMLSanitizer.isSanitized("<b>hi</b> <i>there</i>"));
        }

        @Test
        public void keepsHttpAnchorsAndImages() {
            assertEquals("<a href=\"http://e.com\">x</a>",
                    HTMLSanitizer.sanitize("<a href=\"http://e.com\">x</a>"));
            assertEquals("<img src=\"https://e.com/a.png\" width=\"10\">",
                    HTMLSanitizer.sanitize("<img src=\"https://e.com/a.png\" width=\"10\">"));
        }

        @Test
        public void keepsMailtoAnchorsWhoseDomainLooksReal() {
            // Comment authors link their address; the sanitizer validates the
            // part after the '@' as if it were a host name.
            assertEquals("<a href=\"mailto:a@b.com\">x</a>",
                    HTMLSanitizer.sanitize("<a href=\"mailto:a@b.com\">x</a>"));
            assertEquals("x", HTMLSanitizer.sanitize("<a href=\"mailto:nonsense\">x</a>"));
        }

        @Test
        public void dropsWidthAndHeightThatAreNotANumberOrPercentage() {
            // "10px" is not valid as an HTML attribute value and is also the
            // shape an attacker uses to smuggle a quote out of the attribute.
            assertEquals("<img src=\"http://e.com/a.png\">",
                    HTMLSanitizer.sanitize("<img src=\"http://e.com/a.png\" width=\"10px\">"));
            assertEquals("<img src=\"http://e.com/a.png\" width=\"50%\">",
                    HTMLSanitizer.sanitize("<img src=\"http://e.com/a.png\" width=\"50%\">"));
        }

        @Test
        public void encodesOrdinaryAttributeValues() {
            // Anything not specially handled (href, src, style, width, on*) is
            // encoded, so a quote inside the value cannot close the attribute
            // early and start a new one.
            assertEquals("<p title=\"it&#39;s\">x</p>",
                    HTMLSanitizer.sanitize("<p title=\"it's\">x</p>"));
        }
    }

    /**
     * The responsive figure/picture block the [image] shortcode emits must
     * survive sanitizing intact -- it is generated by us, after which the
     * sanitizer runs over the whole entry (Stage 2 Wave 1 T3).
     */
    @Nested
    class ShortcodeFigureMarkup {

        private static final String FIGURE = "<figure class=\"shortcode-image\">\n"
                + "<picture>\n"
                + "<source type=\"image/webp\" srcset=\"http://e.com/m/1?w=480 480w, http://e.com/m/1 500w\" sizes=\"100vw\">\n"
                + "<img src=\"http://e.com/m/1\" srcset=\"http://e.com/m/1?w=480 480w, http://e.com/m/1 500w\" sizes=\"100vw\" width=\"500\" height=\"333\" alt=\"A hawk\" loading=\"lazy\" data-blurhash=\"LEHV6nWB2yk8\">\n"
                + "</picture>\n"
                + "<figcaption>A hawk</figcaption>\n"
                + "</figure>";

        @Test
        public void keepsTheFigurePictureSourceStructure() {
            String out = HTMLSanitizer.sanitize(FIGURE);
            assertTrue(out.contains("<figure class=\"shortcode-image\">"), out);
            assertTrue(out.contains("<picture>"), out);
            assertTrue(out.contains("<source type=\"image/webp\""), out);
            assertTrue(out.contains("<figcaption>A hawk</figcaption>"), out);
            assertTrue(out.contains("</figure>"), out);
            assertTrue(HTMLSanitizer.isSanitized(FIGURE),
                    "the shortcode's own markup must count as clean, or every "
                            + "entry using [image] is flagged invalid");
        }

        @Test
        public void keepsSrcsetSizesLoadingAndTheBlurhashDataAttribute() {
            String out = HTMLSanitizer.sanitize(FIGURE);
            assertTrue(out.contains("srcset=\"http://e.com/m/1?w=480 480w, http://e.com/m/1 500w\""), out);
            assertTrue(out.contains("sizes=\"100vw\""), out);
            assertTrue(out.contains("loading=\"lazy\""), out);
            assertTrue(out.contains("data-blurhash=\"LEHV6nWB2yk8\""),
                    "the data- prefix must survive; the attribute pattern used to "
                            + "truncate names at the dash:\n" + out);
        }

        @Test
        public void doesNotInventAClosingSourceTag() {
            // source is a void element; if the sanitizer pushed it as an open
            // tag it would append </source> when the document closes.
            assertFalse(HTMLSanitizer.sanitize(FIGURE).contains("</source>"));
        }

        @Test
        public void stillStripsScriptSmuggledInsideAFigure() {
            String out = HTMLSanitizer.sanitize(
                    "<figure><script>alert(1)</script><figcaption onclick=\"x()\">c</figcaption></figure>");
            assertFalse(out.contains("<script"), out);
            assertFalse(out.contains("onclick"), out);
        }
    }

    /**
     * The [gallery] shortcode's justified-grid markup must round-trip: the
     * grid packs rows with {@code flex-grow: var(--ar)}, so the CSS custom
     * properties on the inline styles and the lightbox's data-* payload on
     * the anchors are load-bearing, not decoration.
     */
    @Nested
    class GalleryGridMarkup {

        private static final String GRID = "<div class=\"jgrid\" style=\"--row-h:280px;\">\n"
                + "<figure style=\"--ar:1.3405;\">\n"
                + "<a href=\"http://e.com/m/1\" data-pswp-width=\"500\" data-pswp-height=\"373\""
                + " data-caption=\"Hawk at dusk\" data-exif-camera=\"NIKON Z 6\""
                + " data-exif-aperture=\"f/2.8\" data-exif-iso=\"400\""
                + " data-blurhash=\"LEHV6nWB2yk8\">\n"
                + "<img src=\"http://e.com/m/1\" srcset=\"http://e.com/m/1?w=480 480w, http://e.com/m/1 500w\""
                + " sizes=\"375px\" width=\"500\" height=\"373\" alt=\"hawk.jpg\""
                + " loading=\"lazy\" decoding=\"async\" data-blurhash=\"LEHV6nWB2yk8\""
                + " style=\"background-color:#979695\">\n"
                + "</a>\n"
                + "<figcaption>Hawk at dusk</figcaption>\n"
                + "</figure>\n"
                + "</div>";

        @Test
        public void theArCustomPropertySurvivesOnTheFigureStyle() {
            // THE decision test for the justified grid: if a CSS custom
            // property were dropped by the declaration-by-declaration style
            // re-parse, the shortcode would have to fall back to bucketed
            // aspect-ratio classes instead of emitting style="--ar:...".
            String out = HTMLSanitizer.sanitize("<figure style=\"--ar:1.3405;\">x</figure>");
            assertTrue(out.contains("style=\"--ar:1.3405;\""),
                    "the --ar custom property was mangled or dropped:\n" + out);
        }

        @Test
        public void theRowHeightCustomPropertySurvivesOnTheGridContainer() {
            String out = HTMLSanitizer.sanitize(GRID);
            assertTrue(out.contains("<div class=\"jgrid\" style=\"--row-h:280px;\">"), out);
        }

        @Test
        public void theLightboxAnchorKeepsItsHrefAndDataPayload() {
            String out = HTMLSanitizer.sanitize(GRID);
            assertTrue(out.contains("<a href=\"http://e.com/m/1\""), out);
            assertTrue(out.contains("data-pswp-width=\"500\""), out);
            assertTrue(out.contains("data-pswp-height=\"373\""), out);
            assertTrue(out.contains("data-caption=\"Hawk at dusk\""), out);
            assertTrue(out.contains("data-exif-camera=\"NIKON Z 6\""), out);
            assertTrue(out.contains("data-exif-aperture=\"f/2.8\""), out);
            assertTrue(out.contains("data-exif-iso=\"400\""), out);
            assertTrue(out.contains("data-blurhash=\"LEHV6nWB2yk8\""), out);
        }

        @Test
        public void theGridImageKeepsItsResponsiveAttributes() {
            String out = HTMLSanitizer.sanitize(GRID);
            assertTrue(out.contains("<img src=\"http://e.com/m/1\""), out);
            assertTrue(out.contains("srcset=\"http://e.com/m/1?w=480 480w, http://e.com/m/1 500w\""), out);
            assertTrue(out.contains("sizes=\"375px\""), out);
            assertTrue(out.contains("loading=\"lazy\""), out);
            assertTrue(out.contains("decoding=\"async\""), out);
            assertTrue(out.contains("style=\"background-color:#979695;\""), out);
        }

        @Test
        public void theWholeGridCountsAsClean() {
            assertTrue(HTMLSanitizer.isSanitized(GRID),
                    "the shortcode's own markup must count as clean, or every "
                            + "entry using [gallery] is flagged invalid");
            String out = HTMLSanitizer.sanitize(GRID);
            assertTrue(out.contains("<figcaption>Hawk at dusk</figcaption>"), out);
            assertTrue(out.contains("</figure>"), out);
            assertTrue(out.contains("</div>"), out);
        }

        @Test
        public void aRelativeAnchorHrefStillKillsTheWholeAnchor() {
            // pins why the gallery must emit the absolute media permalink
            String out = HTMLSanitizer.sanitize(
                    "<figure style=\"--ar:1.5;\"><a href=\"/m/1\" data-pswp-width=\"500\">"
                            + "<img src=\"http://e.com/m/1\"></a></figure>");
            assertFalse(out.contains("<a "), out);
            assertFalse(out.contains("data-pswp-width"), out);
        }
    }

    /**
     * The [map] shortcode's markup must round-trip: the pins ride a
     * single-line JSON payload in {@code data-pins} (HTML-encoded quotes, so
     * the sanitizer's own idempotent encoder leaves it byte-identical) and
     * the theme-side initialiser reads it back from {@code dataset.pins}.
     */
    @Nested
    class TravelMapMarkup {

        private static final String PINS = "[{&quot;lat&quot;:48.8584,&quot;lng&quot;:2.2945,"
                + "&quot;label&quot;:&quot;Eiffel Tower&quot;},"
                + "{&quot;lat&quot;:48.8606,&quot;lng&quot;:2.3376}]";

        private static final String MAP = "<div class=\"travel-map\" data-pins=\"" + PINS
                + "\" data-center=\"48.8584,2.2945\" data-zoom=\"13\""
                + " data-route=\"true\"></div>";

        @Test
        public void theWholeMapDivCountsAsClean() {
            assertTrue(HTMLSanitizer.isSanitized(MAP),
                    "the shortcode's own markup must count as clean, or every "
                            + "entry using [map] is flagged invalid");
        }

        @Test
        public void theDataPinsJsonPayloadSurvivesByteIdentical() {
            String out = HTMLSanitizer.sanitize(MAP);
            assertTrue(out.contains("data-pins=\"" + PINS + "\""),
                    "the encoded JSON payload must survive the sanitizer's own "
                            + "(idempotent) encode pass byte-identical:\n" + out);
            assertTrue(out.contains("<div class=\"travel-map\""), out);
            assertTrue(out.contains("data-center=\"48.8584,2.2945\""), out);
            assertTrue(out.contains("data-zoom=\"13\""), out);
            assertTrue(out.contains("data-route=\"true\""), out);
            assertTrue(out.contains("</div>"), out);
        }

        @Test
        public void aMultiLinePinsPayloadWouldDestroyTheWholeTag() {
            // pins why the emitter must write the JSON on ONE line: a \n
            // inside the attribute value stops the tag from matching the
            // sanitizer's tag pattern at all, so the entire div is encoded
            // away as literal text and no map ever reaches the reader.
            String out = HTMLSanitizer.sanitize(
                    "<div class=\"travel-map\" data-pins=\"[\n{&quot;lat&quot;:1.0}]\"></div>");
            assertFalse(out.contains("<div class=\"travel-map\""), out);
            assertTrue(out.contains("&lt;div"), out);
        }

        @Test
        public void aScriptPayloadSmuggledThroughAPinLabelStaysInertText() {
            // '"><script> in a label, JSON-escaped then HTML-encoded by the
            // emitter: it must stay inside the attribute value as text.
            String smuggled = "<div class=\"travel-map\" data-pins=\"[{&quot;lat&quot;:1.0,"
                    + "&quot;label&quot;:&quot;\\&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;&quot;}]\"></div>";
            String out = HTMLSanitizer.sanitize(smuggled);
            assertFalse(out.contains("<script"), out);
            assertTrue(HTMLSanitizer.isSanitized(smuggled), smuggled);
        }
    }

    /**
     * The [cta] shortcode's card markup must round-trip: the sanitizer
     * validates anchor hrefs with UrlValidator and silently deletes the
     * whole anchor on failure, which is exactly why the handler validates
     * the URL itself and returns null instead of emitting a doomed anchor.
     */
    @Nested
    class CtaCardMarkup {

        private static final String HREF = "https://booking.example.com/cottage"
                + "?a=1&utm_source=blog&utm_medium=blog&utm_campaign=summer-cottage";

        private static final String CTA = "<a class=\"cta-card\" href=\"" + HREF
                + "\" rel=\"nofollow sponsored noopener\" target=\"_blank\">"
                + "<span class=\"cta-label\">Book this cottage</span>"
                + "<span class=\"cta-note\">From €120/night</span></a>";

        @Test
        public void theWholeCardCountsAsClean() {
            assertTrue(HTMLSanitizer.isSanitized(CTA),
                    "the shortcode's own markup must count as clean, or every "
                            + "entry using [cta] is flagged invalid");
        }

        @Test
        public void keepsTheHrefWithItsUtmQueryAndTheRelTargetPair() {
            String out = HTMLSanitizer.sanitize(CTA);
            assertTrue(out.contains("href=\"" + HREF + "\""),
                    "the UTM query string must survive untouched:\n" + out);
            assertTrue(out.contains("rel=\"nofollow sponsored noopener\""), out);
            assertTrue(out.contains("target=\"_blank\""), out);
            assertTrue(out.contains("<span class=\"cta-label\">Book this cottage</span>"), out);
            assertTrue(out.contains("<span class=\"cta-note\">From €120/night</span>"), out);
        }

        @Test
        public void aRelativeHrefKillsTheWholeCardAnchor() {
            // pins why CtaShortcode must validate the URL itself: emitting
            // this anchor would have the sanitizer silently delete it.
            String out = HTMLSanitizer.sanitize(
                    "<a class=\"cta-card\" href=\"/book\" rel=\"nofollow\">"
                            + "<span class=\"cta-label\">Book</span></a>");
            assertFalse(out.contains("<a "), out);
            assertFalse(out.contains("cta-card"), out);
        }
    }

    /**
     * The [faq] shortcode's definition-list markup must round-trip:
     * dl/dt/dd are allow-listed (details/summary are NOT, which is why the
     * shortcode emits a dl), and answers are entry HTML flowing through
     * this sanitizer like any other body text.
     */
    @Nested
    class FaqMarkup {

        private static final String FAQ = "<dl class=\"faq\">\n"
                + "<dt>When is the best month?</dt>\n"
                + "<dd>June, with <a href=\"https://example.com/guide\">this guide</a>.</dd>\n"
                + "</dl>";

        @Test
        public void keepsTheDlDtDdStructure() {
            assertTrue(HTMLSanitizer.isSanitized(FAQ),
                    "the shortcode's own markup must count as clean, or every "
                            + "entry using [faq] is flagged invalid");
            String out = HTMLSanitizer.sanitize(FAQ);
            assertTrue(out.contains("<dl class=\"faq\">"), out);
            assertTrue(out.contains("<dt>When is the best month?</dt>"), out);
            assertTrue(out.contains("<dd>June, with <a href=\"https://example.com/guide\">"
                    + "this guide</a>.</dd>"), out);
            assertTrue(out.contains("</dl>"), out);
        }

        @Test
        public void stillStripsScriptSmuggledInsideAnAnswer() {
            String out = HTMLSanitizer.sanitize(
                    "<dl class=\"faq\"><dt>q</dt>"
                            + "<dd>\"><script>alert(1)</script></dd></dl>");
            assertFalse(out.contains("<script"), out);
            assertTrue(out.contains("<dd>"), out);
        }
    }

    @Nested
    class Structure {

        @Test
        public void closesTagsThatTheAuthorLeftOpen() {
            // An unclosed <b> in a comment would otherwise bold the rest of the
            // page, including other people's comments.
            assertEquals("<b>hi</b>", HTMLSanitizer.sanitize("<b>hi"));
            assertEquals("<div><p>hi</p></div>", HTMLSanitizer.sanitize("<div><p>hi"));
        }

        @Test
        public void closesTagsLeftOpenInsideAnEnclosingElementWhenThatOneCloses() {
            // Closing </div> pops the still-open <b> with it, so the output
            // stays balanced even though the input was not.
            assertEquals("<div><b>hi</b></div>", HTMLSanitizer.sanitize("<div><b>hi</div>"));
        }

        @Test
        public void doesNotCloseStandaloneTags() {
            // <br>, <hr> and <img> have no end tag; pushing them on the stack
            // would produce a bogus "</br>".
            assertEquals("a<br>b", HTMLSanitizer.sanitize("a<br>b"));
            assertFalse(HTMLSanitizer.sanitize("a<br>b").contains("</br>"));
        }

        @Test
        public void rejectsTableRowsThatAreNotInsideATable() {
            // A stray <tr> escapes its intended container and can drag the
            // rest of the page into an implied table in some browsers.
            assertEquals("<table><tr><td>x</td></tr></table>",
                    HTMLSanitizer.sanitize("<table><tr><td>x</td></tr></table>"));
            assertEquals("x", HTMLSanitizer.sanitize("<tr><td>x</td></tr>"));
            assertEquals("x", HTMLSanitizer.sanitize("<td>x</td>"));
        }

        @Test
        public void removesCommentsAndTerminatesUnclosedOnes() {
            // A comment that is never closed would swallow the rest of the
            // document; the sanitizer records it and closes it in 'val'.
            HTMLSanitizer.SanitizeResult closed = HTMLSanitizer.sanitizer("<!-- hi --> x");
            assertEquals(" x", closed.html);
            assertFalse(closed.isValid);

            HTMLSanitizer.SanitizeResult unclosed = HTMLSanitizer.sanitizer("<!-- hi");
            assertTrue(unclosed.val.endsWith("-->"),
                    "An unterminated comment must be closed in the saved value, otherwise "
                            + "everything after it disappears when the page renders. Got: " + unclosed.val);
        }

        @Test
        public void getTextReturnsTheReadableContentWithoutTags() {
            // Used for excerpts and feed summaries.
            assertEquals("hi there", HTMLSanitizer.getText("<b>hi</b> there"));
        }

        @Test
        public void emptyInputIsValidAndProducesNothing() {
            HTMLSanitizer.SanitizeResult result = HTMLSanitizer.sanitizer("");
            assertTrue(result.isValid);
            assertEquals("", result.html);
            assertEquals("", result.text);
            assertEquals("", result.val);
        }
    }

    @Nested
    class Encoders {

        @Test
        public void encodeEscapesQuotesAndBracketsAndTurnsNewlinesIntoBreaks() {
            assertEquals("a&lt;b&gt;&quot;c&quot;<br>d", HTMLSanitizer.encode("a<b>\"c\"\nd"));
            assertEquals("&#39;", HTMLSanitizer.encode("'"));
        }

        @Test
        public void encodeTreatsNullAsEmptyRatherThanPrintingNull() {
            assertEquals("", HTMLSanitizer.encode(null));
        }

        @Test
        public void theIndividualEncodersPassNullThrough() {
            // These are called directly from templates where a null field is
            // normal; only encode() collapses null to "".
            assertNull(HTMLSanitizer.htmlEncodeApexes(null));
            assertNull(HTMLSanitizer.htmlEncodeTag(null));
            assertNull(HTMLSanitizer.convertLineFeedToBR(null));
            assertNull(HTMLSanitizer.removeLineFeed(null));
        }

        @Test
        public void removeLineFeedFlattensEveryLineBreakToASpace() {
            assertEquals("a b c d", HTMLSanitizer.removeLineFeed("a\nb\rc\fd"));
        }

        @Test
        public void replaceAllNoRegexTreatsItsArgumentsAsLiteralText() {
            // The point of this helper is that "." and "$" are not special;
            // a regex-based replace would corrupt user content.
            assertEquals("aXc", HTMLSanitizer.replaceAllNoRegex("a.c", ".", "X"));
            assertEquals("a$b", HTMLSanitizer.replaceAllNoRegex("a?b", "?", "$"));
            assertEquals("xbxbx", HTMLSanitizer.replaceAllNoRegex("ababa", "a", "x"));
        }

        @Test
        public void replaceAllNoRegexIsSafeWithEmptySearchAndNullSource() {
            // An empty search string would otherwise loop forever.
            assertEquals("abc", HTMLSanitizer.replaceAllNoRegex("abc", "", "x"));
            assertEquals("", HTMLSanitizer.replaceAllNoRegex(null, "a", "x"));
        }
    }

    @Test
    public void conditionallySanitizeNeverThrowsOnNull() {
        // Whether this sanitizes at all depends on the weblogAdminsUntrusted
        // setting, but the null guard has to hold either way -- it is called
        // on optional weblog fields.
        assertNull(HTMLSanitizer.conditionallySanitize(null));
    }

    @Test
    public void conditionallySanitizeCleansContentUnderTheShippedConfiguration() {
        // roller.properties ships weblogAdminsUntrusted=true, so out of the box
        // this call must actually sanitize. (An installation that trusts its
        // weblog admins can turn the flag off, at which point this is a
        // pass-through by design.)
        assertTrue(HTMLSanitizer.xssEnabled,
                "This test assumes the shipped default weblogAdminsUntrusted=true; if the "
                        + "default changed, update the expectation below with it.");
        assertEquals("alert(1)", HTMLSanitizer.conditionallySanitize("<script>alert(1)</script>"));
    }
}

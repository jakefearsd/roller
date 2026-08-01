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

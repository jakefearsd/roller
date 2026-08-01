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
package org.apache.roller.weblogger.business.shortcodes;

import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The parsing/dispatch matrix for {@link ShortcodeExpander}: registered
 * shortcodes expand, unknown ones pass through untouched (forward-compat
 * with shortcodes that ship in later waves), {@code [[...]]} escapes, and
 * malformed or failing shortcodes never damage the surrounding text.
 */
class ShortcodeExpanderTest {

    private final WeblogEntry entry = new WeblogEntry();

    /** Renders [upper]body[/upper] (or self-closing [upper word=x]) as upper-case. */
    private static ShortcodeHandler upper() {
        return new ShortcodeHandler() {
            @Override
            public String getName() {
                return "upper";
            }

            @Override
            public String render(Map<String, String> attributes, String body, WeblogEntry entry) {
                String source = body != null ? body : attributes.getOrDefault("word", "");
                return source.toUpperCase();
            }
        };
    }

    /** Echoes its attributes so tests can see exactly what was parsed. */
    private static ShortcodeHandler echoAttrs() {
        return new ShortcodeHandler() {
            @Override
            public String getName() {
                return "echo";
            }

            @Override
            public String render(Map<String, String> attributes, String body, WeblogEntry entry) {
                StringBuilder out = new StringBuilder("{");
                attributes.forEach((k, v) -> out.append(k).append('=').append(v).append(';'));
                return out.append('}').toString();
            }
        };
    }

    private final ShortcodeExpander expander =
            new ShortcodeExpander(List.of(upper(), echoAttrs()));

    // ------------------------------------------------------------- expansion

    @Test
    void aSelfClosingShortcodeIsReplaced() {
        assertEquals("before LOUD after",
                expander.expand(entry, "before [upper word=loud] after"));
    }

    @Test
    void aBodyShortcodeFeedsItsBodyToTheHandler() {
        assertEquals("say IT LOUD now",
                expander.expand(entry, "say [upper]it loud[/upper] now"));
    }

    @Test
    void anExplicitlySelfClosedShortcodeDoesNotCaptureALaterCloser() {
        // The trailing slash says "no body", so the stray [/upper] is just text.
        assertEquals("X and [/upper]",
                expander.expand(entry, "[upper word=x /] and [/upper]"));
    }

    @Test
    void shortcodeNamesAreCaseInsensitive() {
        assertEquals("LOUD", expander.expand(entry, "[UPPER word=loud]"));
    }

    @Test
    void multipleShortcodesInOneTextAllExpand() {
        assertEquals("A then B",
                expander.expand(entry, "[upper word=a] then [upper word=b]"));
    }

    @Test
    void differentShortcodesNestInsideABody() {
        assertEquals("OUTER INNER TEXT",
                expander.expand(entry, "[upper]outer [upper word=inner] text[/upper]"));
    }

    @Test
    void attributesParseQuotedSingleQuotedAndBareValues() {
        assertEquals("{a=one two;b=three;c=4;}",
                expander.expand(entry, "[echo a=\"one two\" b='three' c=4]"));
    }

    @Test
    void attributeNamesAreLowerCased() {
        assertEquals("{key=v;}", expander.expand(entry, "[echo KEY=v]"));
    }

    // ------------------------------------------------- unknown / passthrough

    @Nested
    class Passthrough {

        @Test
        void anUnknownShortcodePassesThroughUnchanged() {
            String text = "keep [gallery ids=\"1,2\"]my images[/gallery] intact";
            assertEquals(text, expander.expand(entry, text),
                    "unknown shortcodes belong to future waves; today's expander "
                            + "must not touch them");
        }

        @Test
        void anEscapedUnknownShortcodeAlsoPassesThroughUnchanged() {
            // [[gallery]] only un-escapes once "gallery" is a registered name.
            String text = "docs say [[gallery ids=\"1\"]]";
            assertEquals(text, expander.expand(entry, text));
        }

        @Test
        void textWithoutShortcodesComesBackAsTheSameInstance() {
            String text = "plain <b>html</b> with no brackets";
            assertSame(text, expander.expand(entry, text),
                    "the no-shortcode fast path must not copy every entry on every render");
        }

        @Test
        void nullTextStaysNull() {
            assertNull(expander.expand(entry, null));
        }

        @Test
        void emptyTextStaysEmpty() {
            assertEquals("", expander.expand(entry, ""));
        }
    }

    // -------------------------------------------------------------- escaping

    @Nested
    class Escaping {

        @Test
        void aDoubledBracketRendersTheLiteralShortcode() {
            assertEquals("write [upper word=x] to shout",
                    expander.expand(entry, "write [[upper word=x]] to shout"),
                    "[[...]] is how authors document a shortcode without running it");
        }

        @Test
        void anEscapedShortcodeNextToARealOneOnlySuppressesItself() {
            assertEquals("[upper word=a] vs B",
                    expander.expand(entry, "[[upper word=a]] vs [upper word=b]"));
        }

        @Test
        void aDoubledOpeningBracketWithoutADoubledCloserStillExpands() {
            // "[[upper word=x]" is a stray literal bracket followed by a real
            // shortcode, matching how the escape form is defined ([[...]]).
            assertEquals("[X", expander.expand(entry, "[[upper word=x]"));
        }
    }

    // ------------------------------------------------------------- malformed

    @Nested
    class Malformed {

        @Test
        void anUnterminatedTagIsLeftAlone() {
            String text = "oops [upper word=x with no close bracket";
            assertEquals(text, expander.expand(entry, text));
        }

        @Test
        void aCloserWithNoOpenerIsLeftAlone() {
            String text = "stray [/upper] closer";
            assertEquals(text, expander.expand(entry, text));
        }

        @Test
        void aBodyShortcodeMissingItsCloserFallsBackToSelfClosing() {
            assertEquals("LOUD and the rest stays",
                    expander.expand(entry, "[upper word=loud] and the rest stays"));
        }

        @Test
        void bracketsThatAreNotShortcodesAtAllAreLeftAlone() {
            String text = "arrays[0] and [1,2,3] and [ spaced ]";
            assertEquals(text, expander.expand(entry, text));
        }
    }

    // ---------------------------------------------------------- failure modes

    @Nested
    class HandlerFailures {

        private final ShortcodeExpander failing = new ShortcodeExpander(List.of(
                new ShortcodeHandler() {
                    @Override
                    public String getName() {
                        return "boom";
                    }

                    @Override
                    public String render(Map<String, String> attributes, String body,
                            WeblogEntry entry) {
                        throw new IllegalStateException("boom");
                    }
                },
                new ShortcodeHandler() {
                    @Override
                    public String getName() {
                        return "shy";
                    }

                    @Override
                    public String render(Map<String, String> attributes, String body,
                            WeblogEntry entry) {
                        return null;
                    }
                },
                upper()));

        @Test
        void aThrowingHandlerLeavesItsShortcodeAsWritten() {
            assertEquals("a [boom id=1] b",
                    failing.expand(entry, "a [boom id=1] b"),
                    "a broken handler must leave the author's text visible, not "
                            + "take out the page");
        }

        @Test
        void aNullReturningHandlerLeavesItsShortcodeAsWrittenIncludingBody() {
            assertEquals("a [shy]body[/shy] b",
                    failing.expand(entry, "a [shy]body[/shy] b"),
                    "null is the handler's 'cannot render' signal and must preserve "
                            + "the original text verbatim");
        }

        @Test
        void aFailingShortcodeDoesNotStopItsNeighborsFromExpanding() {
            assertEquals("[boom] X [shy]",
                    failing.expand(entry, "[boom] [upper word=x] [shy]"));
        }
    }
}

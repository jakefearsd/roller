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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared {@code [q]..[/q][a]..[/a]} parser: the single source of truth
 * for both the [faq] shortcode's definition list and the FAQPage JSON-LD
 * emission (Wave 3 T4). The pair grammar is strict -- a malformed body
 * parses to null so the shortcode leaves the author's text visible instead
 * of rendering half an FAQ.
 */
class FaqBlocksTest {

    // ------------------------------------------------------------ parsePairs

    @Test
    void parsesQuestionAnswerPairsInOrder() {
        List<FaqBlocks.Qa> pairs = FaqBlocks.parsePairs(
                "[q]Best month?[/q][a]June.[/a]\n"
                        + "[q]Need a car?[/q][a]Yes, <b>definitely</b>.[/a]");

        assertEquals(2, pairs.size());
        assertEquals("Best month?", pairs.get(0).question());
        assertEquals("June.", pairs.get(0).answer());
        assertEquals("Need a car?", pairs.get(1).question());
        assertEquals("Yes, <b>definitely</b>.", pairs.get(1).answer());
    }

    @Test
    void whitespaceAroundAndBetweenPairsIsIgnoredAndContentTrimmed() {
        List<FaqBlocks.Qa> pairs = FaqBlocks.parsePairs(
                "\n  [q]\n Q1 \n[/q]\n\n[a]\n A1 \n[/a]  \n");
        assertEquals(1, pairs.size());
        assertEquals("Q1", pairs.get(0).question());
        assertEquals("A1", pairs.get(0).answer());
    }

    @Test
    void tagNamesAreCaseInsensitive() {
        List<FaqBlocks.Qa> pairs = FaqBlocks.parsePairs("[Q]x[/Q][A]y[/A]");
        assertEquals(1, pairs.size());
    }

    // ------------------------------------------------- malformed body -> null

    @Test
    void aQuestionWithoutAnAnswerIsMalformed() {
        assertNull(FaqBlocks.parsePairs("[q]alone[/q]"));
        assertNull(FaqBlocks.parsePairs("[q]one[/q][a]a[/a][q]dangling[/q]"));
    }

    @Test
    void anAnswerBeforeOrWithoutAQuestionIsMalformed() {
        assertNull(FaqBlocks.parsePairs("[a]answer first[/a][q]q[/q]"));
        assertNull(FaqBlocks.parsePairs("[a]only an answer[/a]"));
    }

    @Test
    void unclosedTagsAreMalformed() {
        assertNull(FaqBlocks.parsePairs("[q]never closed"));
        assertNull(FaqBlocks.parsePairs("[q]q[/q][a]never closed"));
    }

    @Test
    void strayTextBetweenPairsIsMalformed() {
        assertNull(FaqBlocks.parsePairs("[q]q[/q] stray prose [a]a[/a]"));
        assertNull(FaqBlocks.parsePairs("intro [q]q[/q][a]a[/a]"));
        assertNull(FaqBlocks.parsePairs("[q]q[/q][a]a[/a] outro"));
    }

    @Test
    void blankQuestionsOrAnswersAreMalformed() {
        assertNull(FaqBlocks.parsePairs("[q]  [/q][a]a[/a]"));
        assertNull(FaqBlocks.parsePairs("[q]q[/q][a][/a]"));
    }

    @Test
    void aBodyWithNoPairsAtAllIsMalformed() {
        assertNull(FaqBlocks.parsePairs(null));
        assertNull(FaqBlocks.parsePairs(""));
        assertNull(FaqBlocks.parsePairs("   \n  "));
        assertNull(FaqBlocks.parsePairs("just prose"));
    }

    // ------------------------------------------- parse (raw entry text, T4)

    @Test
    void parseCollectsPairsFromEveryFaqBlockInRawEntryText() {
        List<FaqBlocks.Qa> pairs = FaqBlocks.parse(
                "intro prose\n"
                        + "[faq][q]Q1[/q][a]A1[/a][/faq]\n"
                        + "middle [gallery dir=\"x\"] prose\n"
                        + "[faq]\n[q]Q2[/q][a]A2[/a]\n[/faq]\noutro");

        assertEquals(2, pairs.size());
        assertEquals("Q1", pairs.get(0).question());
        assertEquals("A2", pairs.get(1).answer());
    }

    @Test
    void aMalformedFaqBlockContributesNothingJustAsItRendersNothing() {
        // the page/head consistency contract: exactly the blocks that render
        // as a <dl> contribute pairs to the JSON-LD
        List<FaqBlocks.Qa> pairs = FaqBlocks.parse(
                "[faq][q]dangling[/q][/faq] [faq][q]Q[/q][a]A[/a][/faq]");
        assertEquals(1, pairs.size());
        assertEquals("Q", pairs.get(0).question());
    }

    @Test
    void escapedFaqSyntaxMentionedInProseIsNotABlock() {
        assertTrue(FaqBlocks.parse(
                "write [[faq]][[q]]...[[/q]][[/faq]] to add an FAQ").isEmpty());
    }

    @Test
    void textWithoutFaqBlocksParsesToNoPairs() {
        assertTrue(FaqBlocks.parse(null).isEmpty());
        assertTrue(FaqBlocks.parse("no faq here").isEmpty());
        assertTrue(FaqBlocks.parse("[q]outside a faq block[/q][a]x[/a]").isEmpty());
    }
}

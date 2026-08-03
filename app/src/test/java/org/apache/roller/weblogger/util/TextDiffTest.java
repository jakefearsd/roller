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

import java.util.List;
import java.util.stream.Collectors;

import org.apache.roller.weblogger.util.TextDiff.Kind;
import org.apache.roller.weblogger.util.TextDiff.Line;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line diff behind the entry revision view.
 */
class TextDiffTest {

    @Test
    void identicalTextProducesNoDiffAtAll() {
        assertTrue(TextDiff.diff("one\ntwo", "one\ntwo").isEmpty(),
                "an unchanged entry must not render a diff full of unchanged lines");
    }

    @Test
    void aChangedLineShowsAsARemovalFollowedByAnAddition() {
        List<Line> diff = TextDiff.diff("keep\nold line\nkeep too", "keep\nnew line\nkeep too");

        assertEquals(List.of(
                new Line(Kind.SAME, "keep"),
                new Line(Kind.REMOVED, "old line"),
                new Line(Kind.ADDED, "new line"),
                new Line(Kind.SAME, "keep too")), diff);
    }

    @Test
    void anInsertedLineIsTheOnlyChange() {
        List<Line> diff = TextDiff.diff("first\nthird", "first\nsecond\nthird");

        assertEquals(List.of(
                new Line(Kind.SAME, "first"),
                new Line(Kind.ADDED, "second"),
                new Line(Kind.SAME, "third")), diff);
    }

    @Test
    void aDeletedLineIsTheOnlyChange() {
        List<Line> diff = TextDiff.diff("first\nsecond\nthird", "first\nthird");

        assertEquals(List.of(
                new Line(Kind.SAME, "first"),
                new Line(Kind.REMOVED, "second"),
                new Line(Kind.SAME, "third")), diff);
    }

    /**
     * A revision of an entry that never had a summary is the common case for
     * the summary pane, and null must read as "added" rather than throwing.
     */
    @Test
    void nullIsTreatedAsEmpty() {
        assertEquals(List.of(new Line(Kind.ADDED, "written")), TextDiff.diff(null, "written"));
        assertEquals(List.of(new Line(Kind.REMOVED, "deleted")), TextDiff.diff("deleted", null));
        assertTrue(TextDiff.diff(null, null).isEmpty());
    }

    @Test
    void lineEndingsDoNotThemselvesCountAsChanges() {
        assertTrue(TextDiff.diff("a\r\nb", "a\nb").isEmpty(),
                "a file saved on another platform must not read as entirely rewritten");
    }

    /**
     * The guard that keeps a pathological entry from allocating an
     * n-by-m table while its author waits: past the cap the diff degrades to
     * "all of it went, all of this arrived", which is still true, just coarse.
     */
    @Test
    void oversizedInputDegradesToAWholesaleReplacement() {
        String big = "line\n".repeat(TextDiff.MAX_LINES + 1);
        List<Line> diff = TextDiff.diff(big, "one line");

        assertTrue(diff.size() > TextDiff.MAX_LINES);
        assertTrue(diff.stream().noneMatch(line -> line.kind() == Kind.SAME),
                "the degraded form makes no claim about which lines survived");
        assertEquals("one line", diff.get(diff.size() - 1).text());
        assertEquals(Kind.ADDED, diff.get(diff.size() - 1).kind());
    }

    @Test
    void aWhollyDifferentTextHasNothingInCommon() {
        String rendered = TextDiff.diff("alpha\nbeta", "gamma\ndelta").stream()
                .map(line -> line.kind() + ":" + line.text())
                .collect(Collectors.joining(" "));

        assertEquals("REMOVED:alpha REMOVED:beta ADDED:gamma ADDED:delta", rendered);
    }
}

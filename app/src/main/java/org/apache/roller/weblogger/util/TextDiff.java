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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A line-by-line diff of two texts, computed at view time.
 *
 * <p>Used to show an author what one entry revision changed. Line granularity
 * is the right unit for Markdown: a paragraph is a line, so a reworded
 * sentence shows as that paragraph replaced rather than as a scatter of
 * character edits.
 *
 * <p>The algorithm is a plain longest-common-subsequence, which costs
 * O(n x m) in time and memory. That is fine for blog entries and hopeless for
 * anything large, so inputs beyond {@link #MAX_LINES} are reported as a whole
 * replacement rather than being allowed to exhaust the heap while an author
 * waits.
 */
public final class TextDiff {

    /**
     * Above this many lines on either side, the diff degrades to "everything
     * replaced". A 4000-line entry would otherwise allocate a 16-million-cell
     * table to tell someone what they already know.
     */
    static final int MAX_LINES = 4000;

    /** What happened to one line. */
    public enum Kind {
        SAME, ADDED, REMOVED
    }

    /**
     * One line of the diff.
     *
     * @param kind whether the line is unchanged, only in the new text, or only
     *             in the old one
     * @param text the line itself, without its terminator
     */
    public record Line(Kind kind, String text) {
    }

    private TextDiff() {
        // static use only
    }

    /**
     * The diff from {@code before} to {@code after}, in reading order:
     * removals appear immediately before the additions that replaced them.
     *
     * <p>Null is treated as empty, so a diff against an entry that never had a
     * summary reads as "the summary was added" rather than throwing.
     */
    public static List<Line> diff(String before, String after) {
        List<String> oldLines = lines(before);
        List<String> newLines = lines(after);

        if (oldLines.equals(newLines)) {
            return Collections.emptyList();
        }
        if (oldLines.size() > MAX_LINES || newLines.size() > MAX_LINES) {
            return wholesaleReplacement(oldLines, newLines);
        }

        int[][] lcs = longestCommonSubsequence(oldLines, newLines);

        List<Line> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < oldLines.size() && j < newLines.size()) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                result.add(new Line(Kind.SAME, oldLines.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                result.add(new Line(Kind.REMOVED, oldLines.get(i)));
                i++;
            } else {
                result.add(new Line(Kind.ADDED, newLines.get(j)));
                j++;
            }
        }
        while (i < oldLines.size()) {
            result.add(new Line(Kind.REMOVED, oldLines.get(i++)));
        }
        while (j < newLines.size()) {
            result.add(new Line(Kind.ADDED, newLines.get(j++)));
        }
        return result;
    }

    /**
     * {@code lcs[i][j]} is the length of the longest common subsequence of
     * {@code oldLines[i..]} and {@code newLines[j..]}. Filled from the end so
     * the walk above can read it forwards.
     */
    private static int[][] longestCommonSubsequence(List<String> oldLines, List<String> newLines) {
        int[][] lcs = new int[oldLines.size() + 1][newLines.size() + 1];
        for (int i = oldLines.size() - 1; i >= 0; i--) {
            for (int j = newLines.size() - 1; j >= 0; j--) {
                lcs[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        return lcs;
    }

    private static List<Line> wholesaleReplacement(List<String> oldLines, List<String> newLines) {
        List<Line> result = new ArrayList<>(oldLines.size() + newLines.size());
        for (String line : oldLines) {
            result.add(new Line(Kind.REMOVED, line));
        }
        for (String line : newLines) {
            result.add(new Line(Kind.ADDED, line));
        }
        return result;
    }

    /**
     * Splits on any line terminator and treats null and empty alike, so a
     * diff never depends on which platform wrote the text.
     */
    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split("\r\n|\r|\n", -1));
    }
}

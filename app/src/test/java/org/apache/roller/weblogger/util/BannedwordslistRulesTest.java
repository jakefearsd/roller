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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how banned-word rules are parsed and matched.
 *
 * <p>{@link BannedwordslistTest} covers the shipped list through the
 * singleton; this class drives the parser directly with its own list, so it
 * can cover the rule syntax (comments, regexes, malformed patterns) without
 * adding rules to the JVM-wide singleton, whose contents never reset.
 *
 * <p>What is at stake in both directions: a rule that fails to match lets spam
 * comments onto the site, and a rule that matches too eagerly silently
 * discards a real reader's comment.
 */
public class BannedwordslistRulesTest {

    /** Builds an isolated list from the given file contents. */
    private Bannedwordslist listOf(Path dir, String contents) {
        try {
            Path file = dir.resolve("bannedwordslist.txt");
            Files.writeString(file, contents, StandardCharsets.UTF_8);
            Bannedwordslist list = new Bannedwordslist();
            list.loadBannedwordslistFromFile(file.toAbsolutePath().toString());
            return list;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nested
    class RuleFileParsing {

        @Test
        public void matchesAPlainWordOnAWordBoundaryAndIgnoresCase(@TempDir Path dir) {
            Bannedwordslist list = listOf(dir, "spamword\n");

            assertTrue(list.isBannedwordslisted("buy spamword now"));
            assertTrue(list.isBannedwordslisted("buy SPAMWORD now"), "rules are case insensitive");
            assertFalse(list.isBannedwordslisted("notspamwordhere"),
                    "A plain rule is anchored on word boundaries; matching inside a longer "
                            + "word would reject innocent comments (the 'Scunthorpe problem').");
        }

        @Test
        public void skipsCommentLines(@TempDir Path dir) {
            // The short "# x" line matters: comment handling looks for a "Last
            // update:" stamp by offset, and a line shorter than that prefix
            // must not throw and abandon the rest of the file.
            Bannedwordslist list = listOf(dir, "# x\n# spamword is only mentioned in a comment\nrealrule\n");

            assertFalse(list.isBannedwordslisted("spamword"),
                    "A word that only appears in a comment line must not become a rule.");
            assertTrue(list.isBannedwordslisted("realrule"));
        }

        @Test
        public void stripsATrailingCommentFromARule(@TempDir Path dir) {
            Bannedwordslist list = listOf(dir, "spamword # added 2004 by someone\n");

            assertTrue(list.isBannedwordslisted("spamword"));
            assertFalse(list.isBannedwordslisted("added"),
                    "The comment text must not become part of the rule, or every comment "
                            + "mentioning 'added' would be rejected.");
        }

        @Test
        public void keepsTheWholeRuleWhenTheCommentFollowsWithoutASpace(@TempDir Path dir) {
            // Regression guard: the comment was stripped from one character
            // *before* the '#', so "spamword# note" became the rule "spamwor"
            // -- which then failed to match "spamword" and matched nothing
            // useful either.
            Bannedwordslist list = listOf(dir, "spamword# added 2004\n");

            assertTrue(list.isBannedwordslisted("buy spamword now"),
                    "A rule written without a space before its comment lost its last "
                            + "character, so the word it was meant to ban got through.");
        }

        @Test
        public void treatsALineContainingAParenthesisAsARegularExpression(@TempDir Path dir) {
            Bannedwordslist list = listOf(dir, "(spam|scam)-site\\d+\n");

            assertTrue(list.isBannedwordslisted("visit spam-site42 today"));
            assertTrue(list.isBannedwordslisted("visit scam-site7 today"));
            assertFalse(list.isBannedwordslisted("visit ham-site42 today"));
        }

        @Test
        public void ignoresBlankLines(@TempDir Path dir) {
            Bannedwordslist list = listOf(dir, "\n\n   \nspamword\n\n");

            assertTrue(list.isBannedwordslisted("spamword"));
            assertFalse(list.isBannedwordslisted("anything else at all"),
                    "A blank line must not turn into a rule that matches everything.");
        }

        @Test
        public void fallsBackToASubstringTestWhenARuleIsNotValidRegexSyntax(@TempDir Path dir) {
            // Rules are compiled as regexes, so an unbalanced bracket in an
            // administrator's list would otherwise throw while checking every
            // single comment.
            Bannedwordslist list = listOf(dir, "[bad\n");

            assertTrue(list.isBannedwordslisted("this is [bad stuff"));
            assertFalse(list.isBannedwordslisted("this is fine"));
        }

        @Test
        public void aListThatIsAlreadyNewerThanTheFileIsNotReloaded(@TempDir Path dir) throws IOException {
            // The file records when it was generated ("# Last update: ..."), and
            // the loader uses that to skip re-reading a file it already has.
            // Here the recorded date is far in the future, so the second file
            // is considered stale and its rules are never picked up.
            Path first = dir.resolve("first.txt");
            Files.writeString(first, "# Last update: 2999/01/01 00:00:00\nfirstrule\n", StandardCharsets.UTF_8);
            Path second = dir.resolve("second.txt");
            Files.writeString(second, "secondrule\n", StandardCharsets.UTF_8);

            Bannedwordslist list = new Bannedwordslist();
            list.loadBannedwordslistFromFile(first.toAbsolutePath().toString());
            list.loadBannedwordslistFromFile(second.toAbsolutePath().toString());

            assertTrue(list.isBannedwordslisted("firstrule"));
            assertFalse(list.isBannedwordslisted("secondrule"),
                    "The list already carried a newer 'Last update' stamp than the second "
                            + "file, so that file must be skipped. If this fails, either the "
                            + "stamp is no longer parsed or the freshness check is gone -- "
                            + "which means the list is re-read from disk on every load.");
        }

        @Test
        public void aFileTimestampedExactlyAtTheRecordedUpdateIsConsideredCurrent(@TempDir Path dir)
                throws IOException, ParseException {
            // Boundary of the freshness check: same instant counts as "already
            // have it". Loading again on equality would re-read (and duplicate)
            // the rules every time the list is consulted.
            String stamp = "2999/01/01 00:00:00";
            Path first = dir.resolve("first.txt");
            Files.writeString(first, "# Last update: " + stamp + "\nfirstrule\n", StandardCharsets.UTF_8);
            Path second = dir.resolve("second.txt");
            Files.writeString(second, "secondrule\n", StandardCharsets.UTF_8);
            long stampMillis = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").parse(stamp).getTime();
            assertTrue(second.toFile().setLastModified(stampMillis));

            Bannedwordslist list = new Bannedwordslist();
            list.loadBannedwordslistFromFile(first.toAbsolutePath().toString());
            list.loadBannedwordslistFromFile(second.toAbsolutePath().toString());

            assertFalse(list.isBannedwordslisted("secondrule"));
        }

        @Test
        public void anOlderStampDoesNotBlockLoading(@TempDir Path dir) throws IOException {
            // Mirror image of the test above: with a stamp in the past the
            // second file is newer and its rules must be added.
            Path first = dir.resolve("first.txt");
            Files.writeString(first, "# Last update: 1999/01/01 00:00:00\nfirstrule\n", StandardCharsets.UTF_8);
            Path second = dir.resolve("second.txt");
            Files.writeString(second, "secondrule\n", StandardCharsets.UTF_8);

            Bannedwordslist list = new Bannedwordslist();
            list.loadBannedwordslistFromFile(first.toAbsolutePath().toString());
            list.loadBannedwordslistFromFile(second.toAbsolutePath().toString());

            assertTrue(list.isBannedwordslisted("secondrule"));
        }

        @Test
        public void missingFileLeavesTheListUsableRatherThanThrowing(@TempDir Path dir) {
            // The path is a configured location that may simply not exist yet;
            // the loader falls back to the copy on the classpath.
            Bannedwordslist list = new Bannedwordslist();
            list.loadBannedwordslistFromFile(dir.resolve("nope.txt").toAbsolutePath().toString());

            assertFalse(list.isBannedwordslisted("four score and seven years ago"));
        }

        @Test
        public void neverFlagsNullOrEmptyContent(@TempDir Path dir) {
            // Comments have optional fields (url, email); each is checked.
            Bannedwordslist list = listOf(dir, "spamword\n");

            assertFalse(list.isBannedwordslisted(null));
            assertFalse(list.isBannedwordslisted(""));
        }

        @Test
        public void toStringShowsBothKindsOfRuleForTheAdminScreen(@TempDir Path dir) {
            Bannedwordslist list = listOf(dir, "spamword\n(spam|scam)-site\n");

            String description = list.toString();
            assertTrue(description.contains("spamword"), description);
            assertTrue(description.contains("(spam|scam)-site"), description);
        }
    }

    @Nested
    class CallerSuppliedRules {

        @Test
        public void extraStringRulesAreCheckedAlongsideTheBuiltInOnes(@TempDir Path dir) {
            // A weblog's own banned words are passed in per check rather than
            // being loaded into the shared list.
            Bannedwordslist list = listOf(dir, "spamword\n");

            assertTrue(list.isBannedwordslisted("mentions weblogword",
                    List.of("weblogword"), List.of()));
            assertTrue(list.isBannedwordslisted("mentions spamword",
                    List.of("weblogword"), List.of()),
                    "The built-in rules must still apply when extra ones are supplied.");
            assertFalse(list.isBannedwordslisted("mentions neither", List.of("weblogword"), List.of()));
        }

        @Test
        public void extraRegexRulesAreCheckedToo(@TempDir Path dir) {
            Bannedwordslist list = listOf(dir, "spamword\n");

            assertTrue(list.isBannedwordslisted("see xyz-123 here",
                    List.of(), List.of(Pattern.compile("xyz-\\d+"))));
        }

        @Test
        public void matchesRulesOnlyIgnoresTheBuiltInList() {
            // Referrer checking deliberately uses only the configured rules.
            assertTrue(Bannedwordslist.matchesRulesOnly("http://spam.example.com",
                    List.of("spam.example.com"), List.of()));
            assertTrue(Bannedwordslist.matchesRulesOnly("http://spam42.example.com",
                    List.of(), List.of(Pattern.compile("spam\\d+"))));
            assertFalse(Bannedwordslist.matchesRulesOnly("http://good.example.com",
                    List.of("spam.example.com"), List.of()));
        }
    }

    @Nested
    class SpamRuleParsing {

        @Test
        public void splitsAWeblogsRuleTextIntoStringAndRegexRules() {
            List<String> stringRules = new ArrayList<>();
            List<Pattern> regexRules = new ArrayList<>();

            Bannedwordslist.populateSpamRules("plainrule\n(a|b)pattern\n# a comment\n",
                    stringRules, regexRules, null);

            assertEquals(List.of("plainrule"), stringRules);
            assertEquals(1, regexRules.size(),
                    "A line starting with '(' is a regex rule; misfiling it as a string rule "
                            + "would make it match only literally.");
            assertEquals("(a|b)pattern", regexRules.get(0).pattern());
        }

        @Test
        public void mergesTheSiteWideRulesWithTheWeblogsOwn() {
            List<String> stringRules = new ArrayList<>();
            List<Pattern> regexRules = new ArrayList<>();

            Bannedwordslist.populateSpamRules("weblogrule", stringRules, regexRules, "siterule");

            assertTrue(stringRules.contains("siterule"), stringRules.toString());
            assertTrue(stringRules.contains("weblogrule"), stringRules.toString());
        }

        @Test
        public void trimsSurroundingWhitespaceFromEachRule() {
            List<String> stringRules = new ArrayList<>();

            Bannedwordslist.populateSpamRules("  padded  \n", stringRules, new ArrayList<>(), null);

            assertEquals(List.of("padded"), stringRules,
                    "Untrimmed rules would never match, because the surrounding spaces "
                            + "become part of the pattern.");
        }

        @Test
        public void aWeblogWithNoRulesContributesNothing() {
            List<String> stringRules = new ArrayList<>();
            List<Pattern> regexRules = new ArrayList<>();

            Bannedwordslist.populateSpamRules(null, stringRules, regexRules, null);
            Bannedwordslist.populateSpamRules("", stringRules, regexRules, null);

            assertTrue(stringRules.isEmpty(),
                    "An empty banned-words setting must not produce an empty rule, which "
                            + "would match every comment on the site.");
            assertTrue(regexRules.isEmpty());
        }
    }
}

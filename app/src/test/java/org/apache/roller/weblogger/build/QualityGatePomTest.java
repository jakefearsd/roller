package org.apache.roller.weblogger.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the static-analysis gate's wiring and its exclusion policy.
 *
 * <p>The gate is only worth having if silencing it is a visible act. These
 * assertions make an undocumented exclusion, or a check quietly demoted to
 * warn-only, fail the build. Sibling of ItHarnessPomTest and
 * ProductionComposeTest.
 */
class QualityGatePomTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir")).getParent();

    private static String read(String relative) throws IOException {
        return Files.readString(REPO.resolve(relative));
    }

    /** The seven PMD rules the spec permits excluding. A rule outside this set is a spec change. */
    private static final List<String> PERMITTED_PMD_EXCLUSIONS = List.of(
            "GuardLogStatement", "ProperLogger", "UncommentedEmptyConstructor",
            "AssignmentInOperand", "UncommentedEmptyMethodBody",
            "UnnecessaryConstructor", "AvoidUsingVolatile");

    /** The three SpotBugs families the spec permits excluding, by representative pattern. */
    private static final List<String> PERMITTED_SPOTBUGS_EXCLUSIONS = List.of(
            "EI_EXPOSE_REP", "SE_TRANSIENT_FIELD_NOT_RESTORED", "THROWS_METHOD_THROWS");

    /**
     * The exact bug patterns making up the three permitted SpotBugs exclusion
     * families in config/spotbugs/exclude.xml, transcribed from that file. This
     * set is closed: a pattern outside it, or a fourth {@code <Match>} family,
     * is a spec change -- update the design doc and this list.
     */
    private static final List<String> PERMITTED_SPOTBUGS_PATTERNS = List.of(
            "EI_EXPOSE_REP", "EI_EXPOSE_REP2", "MS_EXPOSE_REP",
            "SE_TRANSIENT_FIELD_NOT_RESTORED", "SE_COMPARATOR_SHOULD_BE_SERIALIZABLE", "CT_CONSTRUCTOR_THROW",
            "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION", "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
            "THROWS_METHOD_THROWS_CLAUSE_THROWABLE", "REC_CATCH_EXCEPTION",
            "MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR", "MC_OVERRIDABLE_METHOD_CALL_IN_READ_OBJECT");

    @Test
    void allThreeChecksAreBoundToVerifyAndFailTheBuild() throws IOException {
        String appPom = read("app/pom.xml");
        assertTrue(appPom.contains("<id>pmd-check</id>"), "pmd check execution missing");
        assertTrue(appPom.contains("<id>cpd-check</id>"), "cpd check execution missing");
        assertTrue(appPom.contains("<id>spotbugs-check</id>"), "spotbugs check execution missing");

        String parentPom = read("pom.xml");
        assertTrue(parentPom.contains("<failOnViolation>true</failOnViolation>"),
                "PMD/CPD must fail the build, not warn");
        assertTrue(parentPom.contains("<failOnError>true</failOnError>"),
                "SpotBugs must fail the build, not warn");
    }

    @Test
    void everyPmdExclusionIsPermittedAndCarriesAReason() throws IOException {
        String ruleset = read("config/pmd/ruleset.xml");
        Matcher m = Pattern.compile("<exclude\\s+name=\"([^\"]+)\"").matcher(ruleset);
        int found = 0;
        while (m.find()) {
            String rule = m.group(1);
            found++;
            assertTrue(PERMITTED_PMD_EXCLUSIONS.contains(rule),
                    "PMD rule '" + rule + "' is excluded but the spec does not permit it. "
                    + "Adding an exclusion is a spec change: update the design doc and this list.");
            assertTrue(hasPrecedingComment(ruleset, m.start()),
                    "PMD exclusion '" + rule + "' has no justification comment above it");
        }
        assertEquals(PERMITTED_PMD_EXCLUSIONS.size(), found,
                "config/pmd/ruleset.xml must exclude exactly the rules the spec lists");
    }

    @Test
    void everySpotbugsExclusionIsPermittedAndCarriesAReason() throws IOException {
        String filter = read("config/spotbugs/exclude.xml");
        for (String family : PERMITTED_SPOTBUGS_EXCLUSIONS) {
            assertTrue(filter.contains(family), "expected SpotBugs exclusion for " + family);
        }

        Matcher m = Pattern.compile("<Match>").matcher(filter);
        int matchCount = 0;
        while (m.find()) {
            matchCount++;
            assertTrue(hasPrecedingComment(filter, m.start()),
                    "every <Match> in the SpotBugs filter needs a justification comment above it");
        }
        // Closes the set the same way everyPmdExclusionIsPermittedAndCarriesAReason does for PMD:
        // a fourth <Match> family, even with a justification comment, is a spec change.
        assertEquals(3, matchCount,
                "config/spotbugs/exclude.xml must contain exactly the three <Match> families the "
                + "spec permits. Adding a fourth is a spec change: update the design doc and this test.");

        Matcher bugPattern = Pattern.compile("<Bug\\s+pattern=\"([^\"]+)\"").matcher(filter);
        while (bugPattern.find()) {
            for (String pattern : bugPattern.group(1).split(",")) {
                String trimmed = pattern.trim();
                assertTrue(PERMITTED_SPOTBUGS_PATTERNS.contains(trimmed),
                        "SpotBugs bug pattern '" + trimmed + "' is excluded but the spec does not "
                        + "permit it. Adding an exclusion is a spec change: update the design doc "
                        + "and this test.");
            }
        }
    }

    @Test
    void theTemporaryViolationCeilingIsGone() throws IOException {
        String parentPom = read("pom.xml");
        String appPom = read("app/pom.xml");
        assertTrue(!parentPom.contains("pmd.max.violations")
                        && !parentPom.contains("spotbugs.max.violations"),
                "the wave's temporary ceiling properties must be deleted once the tree is at zero");
        assertTrue(!appPom.contains("maxAllowedViolations"),
                "maxAllowedViolations was scaffolding; the gate is zero-tolerance now");
    }

    @Test
    void theDeferredLoggingRulesAreMarkedDeferredNotRejected() throws IOException {
        String ruleset = read("config/pmd/ruleset.xml");
        assertTrue(ruleset.contains("SLF4J"),
                "GuardLogStatement/ProperLogger are deferred pending the SLF4J migration; "
                + "the ruleset must point at that follow-up so it stays discoverable");
    }

    /** True if the text between the previous '>' and this offset contains an XML comment. */
    private static boolean hasPrecedingComment(String xml, int offset) {
        String before = xml.substring(0, offset);
        int lastComment = before.lastIndexOf("-->");
        int lastElement = before.lastIndexOf('>', before.length() - 1);
        // The comment must be the nearest preceding markup.
        return lastComment >= 0 && lastComment >= lastElement - 2;
    }
}

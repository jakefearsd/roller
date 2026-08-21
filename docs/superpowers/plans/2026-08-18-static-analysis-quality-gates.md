# Static-Analysis Quality Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add PMD, CPD and SpotBugs to the Maven build as zero-tolerance gates, and fix the 443 violations that stand between the codebase and zero.

**Architecture:** The three checks bind to `verify` in the `app` module only, configured from the parent `pluginManagement`. Because the tree starts at 443 violations, the gates go in on day one with a **temporary violation ceiling** set to today's count — so the build is green, and *new* violations fail immediately from Task 1 onward. Each fix batch lowers the ceiling. The final task sets the ceiling to zero and deletes the scaffolding.

**Tech Stack:** Maven 3.9, JDK 25, `maven-pmd-plugin` 3.28.0 (PMD 7.26), `spotbugs-maven-plugin` 4.9.8.5, JUnit 6 (Jupiter).

**Spec:** `docs/superpowers/specs/2026-08-18-static-analysis-quality-gates-design.md`

## Global Constraints

- **Never run two Maven builds at once in this working tree.** Implementers share `app/target/`. Check first, and inline the wait in the same command as the build:
  ```bash
  pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR
  ```
  The bracket in `[s]urefirebooter` and the `source/roller` scoping are both load-bearing — see CLAUDE.md.
- **No behavioural change.** This wave is gates plus cleanup. If a fix changes what the program does, it is the wrong fix — except where a task explicitly says otherwise (Task 2's pager extraction, Task 6's charset fixes, Task 11's security fixes), and those carry their own tests.
- **Velocity is lenient and fails silently.** Before deleting or renaming *any* Java member — field, method, or parameter — grep both template trees. A template reference to a deleted member prints as literal text with no error and no log line:
  ```bash
  grep -rn "<memberName>" app/src/main/webapp/themes app/src/main/webapp/WEB-INF/velocity
  ```
- **TDD.** Write the failing test, run it, watch it fail for the reason you expect, then write the code. Characterisation tests (Task 2) are the documented exception: they pass on arrival and their javadoc must say so.
- **Never commit or push unless the human asks.** Each task ends with a commit *step*; run it only when told the task is accepted.
- **Commit message trailers** (every commit):
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD
  ```
- **Exclusion counts are fixed by the spec.** 7 PMD rules, 3 SpotBugs families. Adding an eighth is a spec change, not an implementation decision.

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` (parent) | Plugin versions + shared configuration in `pluginManagement`; the temporary ceiling properties |
| `app/pom.xml` | The three `check` executions bound to `verify` — app module only, never `it-selenium` |
| `config/pmd/ruleset.xml` | PMD rule selection: quickstart + security, minus 7 rules, each with a reason comment |
| `config/spotbugs/exclude.xml` | SpotBugs exclusion filter: 3 families, each with a reason comment |
| `bin/quality-report.sh` | Prints current violation counts and sites per rule. The interface every fix task uses to find its work |
| `app/src/test/java/.../QualityGatePomTest.java` | Pins the wiring and the exclusion policy so neither rots silently |
| `CLAUDE.md` | The policy statement (final task) |

---

### Task 1: Wire the three gates with a temporary ceiling

**Files:**
- Modify: `pom.xml` (add `pluginManagement` entries + ceiling properties)
- Modify: `app/pom.xml` (add three executions)
- Create: `config/pmd/ruleset.xml`
- Create: `config/spotbugs/exclude.xml`
- Create: `bin/quality-report.sh`
- Test: `app/src/test/java/org/apache/roller/weblogger/build/QualityGatePomTest.java`

**Interfaces:**
- Produces: `bin/quality-report.sh` — run with no arguments, prints a per-rule count table for PMD and SpotBugs and the CPD block list. Every later task uses it to enumerate its sites and to confirm its count dropped.
- Produces: parent-pom properties `pmd.max.violations`, `spotbugs.max.violations` — the temporary ceilings, lowered by each later task, deleted in Task 13.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/apache/roller/weblogger/build/QualityGatePomTest.java`:

```java
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
        while (m.find()) {
            assertTrue(hasPrecedingComment(filter, m.start()),
                    "every <Match> in the SpotBugs filter needs a justification comment above it");
        }
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
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
mvn -pl app test -Dtest=QualityGatePomTest
```
Expected: FAIL — `NoSuchFileException: config/pmd/ruleset.xml`, because nothing has been wired yet.

- [ ] **Step 3: Create `config/pmd/ruleset.xml`**

Every `<exclude>` carries a comment; `QualityGatePomTest` fails without one.

```xml
<?xml version="1.0"?>
<ruleset name="Roller"
         xmlns="http://pmd.sourceforge.net/ruleset/2.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://pmd.sourceforge.net/ruleset/2.0.0 https://pmd.sourceforge.io/ruleset_2_0_0.xsd">

    <description>
        Roller's PMD gate: PMD's own quickstart set plus the security category,
        minus seven rules. Zero tolerance -- see
        docs/superpowers/specs/2026-08-18-static-analysis-quality-gates-design.md.

        A rule is excluded only when violating it is SYSTEMATICALLY not a defect
        in this architecture, never merely because there are a lot of them.
        Adding an exclusion means updating the spec and QualityGatePomTest.
    </description>

    <rule ref="rulesets/java/quickstart.xml">
        <!-- DEFERRED, not rejected. 368 hits, all from the commons-logging API,
             which has no parameterized form so every call concatenates. The fix
             is the SLF4J migration (176 files, ~550 call sites) recorded as this
             spec's follow-up; doing it here would bury the gate in a diff nobody
             could review. Delete this exclusion when that wave lands. -->
        <exclude name="GuardLogStatement"/>
        <!-- DEFERRED, same SLF4J migration: 167 hits on declaration shape and
             naming that the migration rewrites wholesale anyway. -->
        <exclude name="ProperLogger"/>
        <!-- Style opinion with no defect class behind it: an empty constructor
             is not improved by a comment saying it is empty. -->
        <exclude name="UncommentedEmptyConstructor"/>
        <!-- while ((line = reader.readLine()) != null) is correct, idiomatic
             Java and the clearest way to write that loop. -->
        <exclude name="AssignmentInOperand"/>
        <!-- Style opinion, same reasoning as UncommentedEmptyConstructor. -->
        <exclude name="UncommentedEmptyMethodBody"/>
        <!-- WRONG for this architecture, not merely noisy: JPA entities are
             REQUIRED to declare a no-arg constructor, and this codebase is
             built on EclipseLink. -->
        <exclude name="UnnecessaryConstructor"/>
        <!-- Directly contradicts SpotBugs AT_STALE_THREAD_WRITE_OF_PRIMITIVE,
             which fires five times here and wants MORE volatile, not less.
             Two gates cannot disagree; SpotBugs is right on this one. -->
        <exclude name="AvoidUsingVolatile"/>
    </rule>

    <!-- Currently zero violations. Included precisely because holding zero
         forever is free, and a future one is worth failing a build over. -->
    <rule ref="category/java/security.xml"/>
</ruleset>
```

- [ ] **Step 4: Create `config/spotbugs/exclude.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Roller's SpotBugs exclusion filter. Three families, 469 of 601 findings.
  Zero tolerance on the remaining 132 -- see
  docs/superpowers/specs/2026-08-18-static-analysis-quality-gates-design.md.

  Every <Match> needs a justification comment; QualityGatePomTest enforces it.
  One-off suppressions belong at the call site as @SuppressFBWarnings with a
  reason, not here -- this file is for whole families only.
-->
<FindBugsFilter>
    <!-- JPA pojo accessors returning/storing Date and arrays (333 findings).
         Defensive copies fight EclipseLink's change tracking, and these pojos
         are not shared across threads. Copying every accessor would be a large
         behavioural change with no defect to show for it. -->
    <Match>
        <Bug pattern="EI_EXPOSE_REP,EI_EXPOSE_REP2,MS_EXPOSE_REP"/>
    </Match>

    <!-- Java-serialization complaints on entities that are never Java-serialized
         (42 findings), plus CT_CONSTRUCTOR_THROW, which is the Spring bean-init
         idiom this codebase is built on. -->
    <Match>
        <Bug pattern="SE_TRANSIENT_FIELD_NOT_RESTORED,SE_COMPARATOR_SHOULD_BE_SERIALIZABLE,CT_CONSTRUCTOR_THROW"/>
    </Match>

    <!-- Style opinions about exception declarations and constructor call graphs
         (94 findings), pervasive and load-bearing in framework-shaped code:
         template methods called from constructors are how the servlet and
         manager hierarchies are written. -->
    <Match>
        <Bug pattern="THROWS_METHOD_THROWS_RUNTIMEEXCEPTION,THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION,THROWS_METHOD_THROWS_CLAUSE_THROWABLE,REC_CATCH_EXCEPTION,MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR,MC_OVERRIDABLE_METHOD_CALL_IN_READ_OBJECT"/>
    </Match>
</FindBugsFilter>
```

- [ ] **Step 5: Add plugin management to the parent `pom.xml`**

Add to `<properties>` (the ceilings are scaffolding — Task 13 deletes them):

```xml
<!-- TEMPORARY, deleted in the final task of the static-analysis wave.
     The tree starts at 307 PMD / 132 SpotBugs violations. Rather than
     leave the build red for a dozen commits, the gate goes in at today's
     count: green now, and any NEW violation fails immediately. Each fix
     batch lowers these. When both reach 0 the properties go away and the
     gate is absolute. This is a scaffold, not the ratcheted ceiling the
     spec rejected -- the difference is that it has a scheduled end. -->
<pmd.max.violations>307</pmd.max.violations>
<spotbugs.max.violations>132</spotbugs.max.violations>
<quality.check.skip>false</quality.check.skip>
```

Add to `<pluginManagement><plugins>`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-pmd-plugin</artifactId>
    <version>3.28.0</version>
    <configuration>
        <rulesets>
            <ruleset>${maven.multiModuleProjectDirectory}/config/pmd/ruleset.xml</ruleset>
        </rulesets>
        <targetJdk>25</targetJdk>
        <includeTests>false</includeTests>
        <printFailingErrors>true</printFailingErrors>
        <failOnViolation>true</failOnViolation>
        <skip>${quality.check.skip}</skip>
        <!-- CPD: 200 tokens. Below that the gate starts demanding refactors
             whose risk exceeds the duplication's cost -- at 100 it flags the
             three render caches, whose expiry contracts genuinely differ. -->
        <minimumTokens>200</minimumTokens>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>net.sourceforge.pmd</groupId>
            <artifactId>pmd-core</artifactId>
            <version>7.26.0</version>
        </dependency>
        <dependency>
            <groupId>net.sourceforge.pmd</groupId>
            <artifactId>pmd-java</artifactId>
            <version>7.26.0</version>
        </dependency>
    </dependencies>
</plugin>
<plugin>
    <groupId>com.github.spotbugs</groupId>
    <artifactId>spotbugs-maven-plugin</artifactId>
    <version>4.9.8.5</version>
    <configuration>
        <effort>Max</effort>
        <threshold>Low</threshold>
        <includeTests>false</includeTests>
        <failOnError>true</failOnError>
        <skip>${quality.check.skip}</skip>
        <excludeFilterFile>${maven.multiModuleProjectDirectory}/config/spotbugs/exclude.xml</excludeFilterFile>
    </configuration>
</plugin>
```

- [ ] **Step 6: Add the executions to `app/pom.xml`**

Inside `<build><plugins>`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-pmd-plugin</artifactId>
    <executions>
        <execution>
            <id>pmd-check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
            <configuration>
                <maxAllowedViolations>${pmd.max.violations}</maxAllowedViolations>
            </configuration>
        </execution>
        <execution>
            <id>cpd-check</id>
            <phase>verify</phase>
            <goals><goal>cpd-check</goal></goals>
        </execution>
    </executions>
</plugin>
<plugin>
    <groupId>com.github.spotbugs</groupId>
    <artifactId>spotbugs-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>spotbugs-check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
            <configuration>
                <maxAllowedViolations>${spotbugs.max.violations}</maxAllowedViolations>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Note: `cpd-check` gets **no** ceiling. CPD has only 4 blocks and Task 2 clears them, so it goes hard immediately.

- [ ] **Step 7: Create `bin/quality-report.sh`**

```bash
#!/usr/bin/env bash
# Prints current static-analysis violation counts and sites.
# Usage: bin/quality-report.sh [rule-name]
#   no args    -- per-rule count tables for PMD and SpotBugs, plus CPD blocks
#   rule-name  -- every file:line for that one rule
#
# Regenerates the reports first, so the numbers are never stale.
set -euo pipefail
cd "$(dirname "$0")/.."

RULE="${1:-}"

if pgrep -f "[s]urefirebooter.*source/roller" >/dev/null; then
    echo "A build is already running in this tree; wait for it." >&2; exit 2
fi

mvn -ntp -q -pl app compile pmd:pmd pmd:cpd spotbugs:spotbugs

python3 - "$RULE" <<'PY'
import sys, xml.etree.ElementTree as ET
from collections import Counter, defaultdict
rule = sys.argv[1] if len(sys.argv) > 1 else ""

def strip(tag): return tag.split('}')[-1]

pmd = defaultdict(list)
try:
    for f in ET.parse('app/target/pmd.xml').getroot().iter():
        if strip(f.tag) != 'file': continue
        path = f.get('name', '').split('/java/')[-1]
        for v in f.iter():
            if strip(v.tag) == 'violation':
                pmd[v.get('rule')].append(f"{path}:{v.get('beginline')}")
except FileNotFoundError:
    pass

sb = defaultdict(list)
try:
    for b in ET.parse('app/target/spotbugsXml.xml').getroot().findall('BugInstance'):
        c = b.find('Class'); s = b.find('SourceLine')
        where = f"{c.get('classname').split('.')[-1] if c is not None else '?'}:{s.get('start') if s is not None else '?'}"
        sb[b.get('type')].append(where)
except FileNotFoundError:
    pass

if rule:
    for site in pmd.get(rule, []) + sb.get(rule, []):
        print(f"  {site}")
    print(f"{rule}: {len(pmd.get(rule, [])) + len(sb.get(rule, []))}")
    sys.exit(0)

print(f"=== PMD: {sum(len(v) for v in pmd.values())} ===")
for r, v in sorted(pmd.items(), key=lambda kv: -len(kv[1])):
    print(f"  {len(v):4}  {r}")
print(f"=== SpotBugs: {sum(len(v) for v in sb.values())} ===")
for r, v in sorted(sb.items(), key=lambda kv: -len(kv[1])):
    print(f"  {len(v):4}  {r}")

try:
    root = ET.parse('app/target/cpd.xml').getroot()
    ds = [d for d in root.iter() if strip(d.tag) == 'duplication']
    print(f"=== CPD @200: {len(ds)} ===")
    for d in ds:
        files = sorted({f.get('path').split('/java/')[-1] for f in d.iter() if strip(f.tag) == 'file'})
        print(f"  {d.get('lines')}L/{d.get('tokens')}t: " + " <-> ".join(files))
except FileNotFoundError:
    pass
PY
```

Then: `chmod +x bin/quality-report.sh`

- [ ] **Step 8: Run the test and watch it pass**

```bash
mvn -pl app test -Dtest=QualityGatePomTest
```
Expected: PASS, 4 tests.

- [ ] **Step 9: Prove the gate is live**

```bash
mvn -pl app verify -DskipTests
```
Expected: BUILD SUCCESS — 307 PMD and 132 SpotBugs violations sit exactly at the ceiling.

Now prove the ceiling actually bites, which is the real test of Step 5:

```bash
mvn -pl app verify -DskipTests -Dpmd.max.violations=306
```
Expected: **BUILD FAILURE**, `You have 307 PMD violations. The maximum allowed is 306.`

- [ ] **Step 10: Confirm the report script works**

```bash
bin/quality-report.sh | head -20
bin/quality-report.sh EmptyCatchBlock
```
Expected: the count tables, then 23 `file:line` entries.

- [ ] **Step 11: Commit**

```bash
git add pom.xml app/pom.xml config/ bin/quality-report.sh \
    app/src/test/java/org/apache/roller/weblogger/build/QualityGatePomTest.java
git commit -m "build: add PMD, CPD and SpotBugs gates at a temporary ceiling

The tree starts at 307 PMD / 132 SpotBugs violations. The gates go in at
that count rather than red, so every commit stays green while new
violations fail from here on. Each fix batch lowers the ceiling; the
final task deletes it.

CPD gets no ceiling -- it has 4 blocks and the next task clears them.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

### Task 2: CPD to zero — extract the pager duplication, mark the caches

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/rendering/pagers/AbstractWeblogEntriesPager.java`
- Modify: `.../pagers/WeblogEntriesDayPager.java`, `.../WeblogEntriesMonthPager.java`, `.../WeblogEntriesLatestPager.java`
- Modify: `.../util/cache/SiteWideCache.java`, `.../util/cache/WeblogPageCache.java`, `.../util/cache/WeblogFeedCache.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/pagers/WeblogEntriesPagerCharacterisationTest.java`

**Interfaces:**
- Consumes: `bin/quality-report.sh` from Task 1.
- Produces: nothing later tasks depend on.

The four blocks at 200 tokens:

| Block | Files | Action |
|---|---|---|
| 306t | `WeblogEntriesDayPager` ↔ `MonthPager` | extract |
| 217t | `DayPager` ↔ `MonthPager` ↔ `LatestPager` | extract |
| 306t | `SiteWideCache` ↔ `WeblogPageCache` | `CPD-OFF` |
| 203t | `WeblogFeedCache` ↔ `WeblogPageCache` | `CPD-OFF` |

- [ ] **Step 1: Write the characterisation test**

This test passes on arrival — it exists to prove the extraction changed nothing. Its javadoc must say so, or a later reader will think it was written backwards.

```java
package org.apache.roller.weblogger.ui.rendering.pagers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CHARACTERISATION TEST -- passes against the code as it stands today, before
 * any change. It exists to prove that extracting the ~300 tokens shared by
 * WeblogEntriesDayPager, MonthPager and LatestPager into their existing common
 * superclass leaves observable pager behaviour identical. A passing run here
 * BEFORE the refactor is the point, not a mistake.
 */
class WeblogEntriesPagerCharacterisationTest {

    private User testUser;
    private Weblog testWeblog;

    @BeforeEach
    void setUp() throws Exception {
        testUser = TestUtils.setupUser("pagerCharUser");
        testWeblog = TestUtils.setupWeblog("pagerCharWeblog", testUser);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void dayPagerReportsItsOwnNavigationUrlsAndTitle() {
        WeblogEntriesDayPager pager = new WeblogEntriesDayPager(
                testWeblog, null, null, null, "20260818", 10);

        assertNotNull(pager.getHomeLink(), "home link");
        assertNotNull(pager.getEntries(), "entries map");
        assertEquals(0, pager.getEntries().size(), "empty weblog has no entries");
    }

    @Test
    void monthPagerReportsItsOwnNavigationUrlsAndTitle() {
        WeblogEntriesMonthPager pager = new WeblogEntriesMonthPager(
                testWeblog, null, null, null, "202608", 10);

        assertNotNull(pager.getHomeLink(), "home link");
        assertEquals(0, pager.getEntries().size(), "empty weblog has no entries");
    }

    @Test
    void latestPagerReportsItsOwnNavigationUrlsAndTitle() {
        WeblogEntriesLatestPager pager = new WeblogEntriesLatestPager(
                testWeblog, null, null, null, null, 10);

        assertNotNull(pager.getHomeLink(), "home link");
        assertEquals(0, pager.getEntries().size(), "empty weblog has no entries");
    }
}
```

**Before writing it, read the three pagers' actual constructor signatures** and match them — the arities above follow the current `AbstractWeblogEntriesPager` shape but must be checked, not assumed:

```bash
grep -n "public WeblogEntries.*Pager(" -A8 \
  app/src/main/java/org/apache/roller/weblogger/ui/rendering/pagers/WeblogEntries*Pager.java
```

- [ ] **Step 2: Run it and confirm it passes on arrival**

```bash
mvn -pl app test -Dtest=WeblogEntriesPagerCharacterisationTest
```
Expected: PASS. If it fails, the constructor signatures were guessed wrong — fix the test, not the pagers.

- [ ] **Step 3: Find the exact duplicated ranges**

```bash
bin/quality-report.sh | grep -A6 "CPD"
```

Then read both sides of each block before touching anything:

```bash
sed -n '<start>,<end>p' app/src/main/java/org/apache/roller/weblogger/ui/rendering/pagers/WeblogEntriesDayPager.java
```

- [ ] **Step 4: Extract the shared code into `AbstractWeblogEntriesPager`**

The superclass already exists and is already the parent of all three, so this is a move, not a new abstraction. Move the identical members up; leave anything that differs per subclass — even by one string — in the subclass. Do not parameterise a difference away by adding a constructor flag; that trades duplication for a conditional and CPD will be satisfied either way.

- [ ] **Step 5: Mark the two cache blocks with CPD-OFF**

In `SiteWideCache`, `WeblogPageCache` and `WeblogFeedCache`, bracket the flagged region:

> **Correction (2026-08-21).** The comment text below is wrong about
> `WeblogFeedCache` and shipped that way. The feed cache passes
> `constructCache(null, ...)`, registers **no** CacheHandler, and expires
> lazily against `weblog.lastModified` exactly as `WeblogPageCache` does;
> `SiteWideCache` is the only render cache CacheManager invalidates. The
> error mattered: it described the two suppressed blocks as one situation
> when they are two. `SiteWideCache.generateKey` ↔ `WeblogPageCache.generateKey`
> really does span differing contracts, but the `WeblogPageCache` ↔
> `WeblogFeedCache` accessor block is duplicated between two caches whose
> contracts are *identical*. The in-code notes and `CLAUDE.md` now say so,
> and `RenderCacheHandlerRegistrationTest` enforces it. This plan text is
> left as written, as the record of what was done at the time.


```java
// CPD-OFF -- The three render caches are deliberately NOT collapsed into a
// shared base. Their expiry contracts genuinely differ: WeblogPageCache has no
// CacheHandler and is expired only lazily against weblog.lastModified, while
// its siblings are invalidated through CacheManager. Unifying them would be a
// behavioural change wearing cleanup's clothes. See CLAUDE.md, Templates.
    ... the flagged block ...
// CPD-ON
```

- [ ] **Step 6: Verify CPD is at zero and behaviour is unchanged**

```bash
mvn -pl app test -Dtest='WeblogEntriesPagerCharacterisationTest,*PagerTest'
bin/quality-report.sh | grep -A6 "CPD"
```
Expected: tests PASS unchanged, `=== CPD @200: 0 ===`.

- [ ] **Step 7: Full verify**

```bash
mvn -pl app verify
```
Expected: BUILD SUCCESS, whole unit suite green.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: clear CPD duplication in the entry pagers

Moves the ~300 tokens shared by the day, month and latest pagers into
AbstractWeblogEntriesPager, which was already their common superclass.
Guarded by a characterisation test written first and passing on arrival.

The two render-cache blocks are marked CPD-OFF rather than extracted:
WeblogPageCache expires lazily against weblog.lastModified and its
siblings do not, so a shared base would change behaviour.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

### Task 3: The 112 pure deletions

**Files:** ~90 files across `app/src/main/java`. Enumerate them, do not guess.

**Interfaces:** Consumes `bin/quality-report.sh`. Produces nothing.

Five rules, all pure removals with no behavioural risk: `UselessParentheses` 44, `UnnecessaryImport` 34, `UnnecessaryFullyQualifiedName` 28, `UnnecessarySemicolon` 4, `UnnecessaryReturn` 2.

- [ ] **Step 1: Enumerate the work**

```bash
for R in UselessParentheses UnnecessaryImport UnnecessaryFullyQualifiedName \
         UnnecessarySemicolon UnnecessaryReturn; do
    echo "=== $R ==="; bin/quality-report.sh "$R"
done
```

- [ ] **Step 2: Apply the deletions**

Work rule by rule, not file by file — one rule's fix is one mental model.

`UnnecessaryImport` — delete the import line:
```java
import java.util.List;        // keep, used
import java.util.ArrayList;   // DELETE, never referenced
```

`UnnecessaryFullyQualifiedName` — the type is already imported, so shorten the use:
```java
// before
java.util.List<String> names = new java.util.ArrayList<>();
// after (import java.util.List / java.util.ArrayList already present)
List<String> names = new ArrayList<>();
```

`UselessParentheses`:
```java
// before
return (a + b);
if ((x == 1) && (y == 2)) {
// after
return a + b;
if (x == 1 && y == 2) {
```
Leave parentheses that clarify mixed-precedence arithmetic even if PMD calls them useless — if PMD still flags one you believe is load-bearing for readability, that is a `@SuppressWarnings("PMD.UselessParentheses")` with a one-line reason, not a silent removal.

- [ ] **Step 3: Confirm it still compiles and the suite is green**

```bash
mvn -pl app test
```
Expected: PASS, same test count as before this task.

- [ ] **Step 4: Confirm the count dropped and lower the ceiling**

```bash
bin/quality-report.sh | head -3
```
Expected: `=== PMD: 195 ===` (307 − 112).

Set `<pmd.max.violations>195</pmd.max.violations>` in the parent `pom.xml` to the number the script actually printed.

- [ ] **Step 5: Verify at the new ceiling**

```bash
mvn -pl app verify
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "style: remove unused imports, redundant qualifiers and useless parens

112 pure deletions with no behavioural component: PMD's
UnnecessaryImport, UnnecessaryFullyQualifiedName, UselessParentheses,
UnnecessarySemicolon and UnnecessaryReturn. Ceiling 307 -> 195.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

### Task 4: Unused members — the Velocity-grep task

**Files:** ~15 files. **This is the task where a careless deletion breaks a template silently.**

Rules: `UnusedLocalVariable` 4, `UnusedFormalParameter` 4, `UnusedPrivateMethod` 2, `UnusedPrivateField` 2, `UnusedNullCheckInEquals` 1, `UnnecessaryModifier` 2, `DLS_DEAD_LOCAL_STORE` 4, `URF_UNREAD_FIELD` 2, `UPM_UNCALLED_PRIVATE_METHOD` 2, `VariableCanBeInlined` 1.

- [ ] **Step 1: Enumerate**

```bash
for R in UnusedLocalVariable UnusedFormalParameter UnusedPrivateMethod \
         UnusedPrivateField UnusedNullCheckInEquals UnnecessaryModifier \
         VariableCanBeInlined DLS_DEAD_LOCAL_STORE URF_UNREAD_FIELD \
         UPM_UNCALLED_PRIVATE_METHOD; do
    echo "=== $R ==="; bin/quality-report.sh "$R"
done
```

- [ ] **Step 2: Grep the templates for every member name before deleting it**

For each member you are about to remove or rename:

```bash
grep -rn "<memberName>" app/src/main/webapp/themes app/src/main/webapp/WEB-INF/velocity
```

A hit means **stop**: the member is reachable from a template, PMD cannot see that, and deleting it prints `$entry.memberName` as literal text into a rendered page with no error and no log line. Keep the member and add:

```java
@SuppressWarnings("PMD.UnusedPrivateMethod") // reached from themes/journal/_day.vm
```

Private members cannot be template-reached, but run the grep anyway — it costs nothing and the rule is "the grep is the check".

- [ ] **Step 3: Apply the deletions**

`UnusedFormalParameter` changes a signature, so check every caller:
```bash
grep -rn "methodName(" app/src/main/java app/src/test/java
```
If the method is public API on a pojo, wrapper or model class, prefer `@SuppressWarnings` over changing the signature — the parameter may exist to satisfy an interface.

- [ ] **Step 4: Run the full suite**

```bash
mvn -pl app test
```
Expected: PASS, same count.

- [ ] **Step 5: Lower the ceilings**

```bash
bin/quality-report.sh | head -3
```
Set `pmd.max.violations` and `spotbugs.max.violations` to the printed numbers.

- [ ] **Step 6: Verify and commit**

```bash
mvn -pl app verify
git add -A
git commit -m "refactor: remove unused locals, fields, methods and parameters

Every removed member was grepped against themes/ and WEB-INF/velocity
first: Velocity resolves references leniently, so a deleted member that a
template reaches prints as literal text with no error and no log line.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

### Task 5: Exception handling — 60 sites

Rules: `EmptyCatchBlock` 23, `PreserveStackTrace` 27, `DE_MIGHT_IGNORE` 10, `AvoidCatchingThrowable` 1, `DCN_NULLPOINTER_EXCEPTION` 1. `EmptyCatchBlock` and `DE_MIGHT_IGNORE` overlap — one fix clears both.

**The spec sets one policy; apply it in this order of preference, do not improvise per site:**
1. Let the exception propagate, if nothing depended on swallowing it.
2. Log at the level the surrounding code already uses.
3. Only if the throw is genuinely expected and ignorable: name the variable `ignored` and add a one-line comment saying **why**. A comment that only says "ignore" is not a reason.

- [ ] **Step 1: Enumerate**

```bash
for R in EmptyCatchBlock PreserveStackTrace AvoidCatchingThrowable \
         DE_MIGHT_IGNORE DCN_NULLPOINTER_EXCEPTION; do
    echo "=== $R ==="; bin/quality-report.sh "$R"
done
```

- [ ] **Step 2: Fix `PreserveStackTrace` — a real diagnostic loss**

At `business/MailProvider.java:81` the cause is discarded outright:

```java
// before -- the NamingException is gone, and with it every clue about WHY
} catch (NamingException ex) {
    throw new StartupException("ERROR looking up mail-session with JNDI name: " + jndiName);
}
// after
} catch (NamingException ex) {
    throw new StartupException(
            "ERROR looking up mail-session with JNDI name: " + jndiName, ex);
}
```

Check the target exception has a `(String, Throwable)` constructor; add one if not, rather than dropping the cause.

- [ ] **Step 3: Fix `EmptyCatchBlock`**

At `business/jpa/JPAPersistenceStrategy.java:484`:

```java
// before
} catch (Exception e) {
    // ignored;
}
// after -- policy rule 3: expected, ignorable, and the comment says why
} catch (Exception ignored) {
    // A refresh of an entity that is no longer managed is a no-op for the
    // caller's purposes; the caller re-reads through the EntityManager either
    // way. Nothing downstream can observe the difference.
}
```

At `business/search/lucene/IndexUtil.java:63` and the theme sites, prefer rule 2 (log) — an index or theme-load failure that vanishes silently is exactly the failure someone will be debugging later.

- [ ] **Step 4: Run the suite**

```bash
mvn -pl app test
```
Expected: PASS. If a test that asserted swallowed-failure behaviour now fails, the exception should not have been allowed to propagate — go back to policy rule 2 for that site.

- [ ] **Step 5: Lower ceilings, verify, commit**

```bash
bin/quality-report.sh | head -3
mvn -pl app verify
git add -A
git commit -m "fix: preserve exception causes and stop swallowing exceptions silently

27 throw sites were discarding the cause, losing the stack trace that
explains the failure. 23 empty catch blocks now either propagate, log, or
name the variable 'ignored' with a reason.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

### Task 6: Charset and locale — 47 sites, two of them security-relevant

Rules: `UseLocaleWithCaseConversions` 19, `DM_CONVERT_CASE` 14, `DM_DEFAULT_ENCODING` 14.

**This task changes behaviour on purpose**, and two sites matter more than the rest:

- `RollerRememberMeServices.makeTokenSignature:67`
- `Utilities.encodePassword:494`

Both feed `String.getBytes()` with the platform-default charset into a signature/hash. Two machines with different default charsets compute **different** signatures for the same input — a remember-me token minted on one host fails on another, and the failure looks like a mysterious auth bug, not an encoding bug. Fix these with `StandardCharsets.UTF_8` and note it in the commit.

- [ ] **Step 1: Enumerate**

```bash
for R in UseLocaleWithCaseConversions DM_CONVERT_CASE DM_DEFAULT_ENCODING; do
    echo "=== $R ==="; bin/quality-report.sh "$R"
done
```

- [ ] **Step 2: Write a failing test for the charset-sensitive signature**

```java
package org.apache.roller.weblogger.ui.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * A remember-me signature must not depend on the JVM's default charset: the
 * same user and token on two hosts with different defaults must produce the
 * same signature, or a token minted on one host is rejected by the other.
 */
class RollerRememberMeServicesCharsetTest {

    @Test
    void signatureIsIdenticalForNonAsciiInputRegardlessOfPlatformCharset() {
        String user = "björn";   // non-ASCII: identical under UTF-8, different under ISO-8859-1

        byte[] utf8 = user.getBytes(StandardCharsets.UTF_8);
        byte[] latin1 = user.getBytes(StandardCharsets.ISO_8859_1);

        // Establishes that this input actually discriminates between charsets;
        // an ASCII-only fixture would pass even against the bug.
        org.junit.jupiter.api.Assertions.assertNotEquals(
                java.util.Arrays.toString(utf8), java.util.Arrays.toString(latin1),
                "fixture must be charset-sensitive or the test proves nothing");

        assertEquals(
                RollerRememberMeServices.makeTokenSignature(0L, user, "pw"),
                RollerRememberMeServices.makeTokenSignature(0L, user, "pw"),
                "signature must be stable");
    }
}
```

Read `makeTokenSignature`'s real signature and visibility first; if it is private, test through the public path that calls it rather than widening visibility for the test.

- [ ] **Step 3: Run it and watch it fail**

```bash
mvn -pl app test -Dtest=RollerRememberMeServicesCharsetTest
```
Expected: FAIL under `-Dfile.encoding=ISO-8859-1` against the unfixed code. If it passes both ways, the fixture is not charset-sensitive — fix the fixture before fixing the code.

- [ ] **Step 4: Apply the charset fixes**

```java
// before
byte[] bytes = value.getBytes();
new InputStreamReader(stream);
new String(bytes);
// after
byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
new InputStreamReader(stream, StandardCharsets.UTF_8);
new String(bytes, StandardCharsets.UTF_8);
```

- [ ] **Step 5: Apply the locale fixes**

At `business/FileContentManagerImpl.java:361-362`:

```java
// before -- in a Turkish locale "I".toLowerCase() is "ı", not "i",
// so an .JPG upload stops matching the jpg allow-list
if (fileName.toLowerCase().endsWith(allowFiles[y].toLowerCase())) {
// after
if (fileName.toLowerCase(Locale.ROOT).endsWith(allowFiles[y].toLowerCase(Locale.ROOT))) {
```

Use `Locale.ROOT` for **machine-facing** comparisons (extensions, MIME types, config keys, URL components) and the user's locale only for text a human reads. Every site in this task is machine-facing.

- [ ] **Step 6: Run the suite**

```bash
mvn -pl app test
```
Expected: PASS, including the new test.

- [ ] **Step 7: Lower ceilings, verify, commit**

```bash
bin/quality-report.sh | head -3
mvn -pl app verify
git add -A
git commit -m "fix: pin charset and locale on machine-facing string operations

47 sites used the platform default. Two of them mattered: the remember-me
token signature and the password encoder both hashed
String.getBytes() with the default charset, so the same credential
produced different bytes on hosts with different defaults. Locale-
sensitive case conversion on file extensions and MIME types now uses
Locale.ROOT -- in a Turkish locale 'I'.toLowerCase() is not 'i', which
silently breaks upload allow-list matching.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

### Task 7: Null-safety and comparisons — 44 sites

Rules: `LiteralsFirstInComparisons` 25, `RCN_REDUNDANT_NULLCHECK_*` 10, `NP_NULL_ON_SOME_PATH*` 5, `NP_NULL_PARAM_DEREF` 2, `NP_LOAD_OF_KNOWN_NULL_VALUE` 3, `UnusedNullCheckInEquals` 1.

- [ ] **Step 1: Enumerate**

```bash
for R in LiteralsFirstInComparisons RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE \
         RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE \
         NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE NP_NULL_ON_SOME_PATH_EXCEPTION \
         NP_NULL_PARAM_DEREF NP_LOAD_OF_KNOWN_NULL_VALUE; do
    echo "=== $R ==="; bin/quality-report.sh "$R"
done
```

- [ ] **Step 2: Fix `LiteralsFirstInComparisons`**

At `boot/SecurityConfig.java:434-435`:

```java
// before -- NPEs if path is null
return path.equals("/roller-ui/rendering/contact.rol")
        || path.equals("/newsletter/subscribe");
// after -- null-safe by construction
return "/roller-ui/rendering/contact.rol".equals(path)
        || "/newsletter/subscribe".equals(path);
```

This is a real null-safety improvement, not a style preference: it removes a whole class of NPE without adding a guard.

- [ ] **Step 3: Fix the null-dereference findings individually**

`RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE` means the value was already dereferenced *before* the check, so the check is dead and the NPE would already have fired. **Move the check earlier**; deleting it is the wrong fix, because it deletes the author's intent instead of honouring it:

```java
// before -- entry.getId() would already have thrown
String id = entry.getId();
if (entry != null) { ... }
// after
if (entry == null) { return; }
String id = entry.getId();
```

`NP_NULL_PARAM_DEREF` at `AbstractWeblogEntriesPager:110` and `SearchResultsPager:81` — read the callers to find which argument can be null and guard at the call site or document the contract.

- [ ] **Step 4: Run, lower ceilings, verify, commit**

```bash
mvn -pl app test
bin/quality-report.sh | head -3
mvn -pl app verify
git add -A
git commit -m "fix: null-safe comparisons and correct dead null checks

Literal-first equals removes an NPE class outright. The redundant null
checks were all placed AFTER a dereference that would already have
thrown, so each is moved earlier rather than deleted -- the author's
intent was right, the position was not.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

### Task 8: Resource handling — 17 sites

Rules: `CloseResource` 13, `OBL_UNSATISFIED_OBLIGATION` 3, `OS_OPEN_STREAM` 1.

Known sites: `DatabaseProvider:144`, `SQLScriptRunner:171`, `SharedThemeFromDir:187` + `:386`, `WebloggerImpl.<init>:107`, `WebloggerRuntimeConfig.getRuntimeConfigDefs:152`.

- [ ] **Step 1: Enumerate**

```bash
for R in CloseResource OBL_UNSATISFIED_OBLIGATION OS_OPEN_STREAM; do
    echo "=== $R ==="; bin/quality-report.sh "$R"
done
```

- [ ] **Step 2: Convert to try-with-resources**

```java
// before
InputStream in = new FileInputStream(f);
Properties p = new Properties();
p.load(in);
in.close();               // skipped entirely if load() throws
// after
Properties p = new Properties();
try (InputStream in = new FileInputStream(f)) {
    p.load(in);
}
```

**Do not close a resource this code does not own.** A `Connection` obtained from a pooled `DataSource` inside `DatabaseProvider` may be the caller's to close — read each site before wrapping it, and if ownership is genuinely the caller's, that is a `@SuppressFBWarnings` with the reason, not a `try`-block that closes someone else's connection.

- [ ] **Step 3: Run, lower ceilings, verify, commit**

```bash
mvn -pl app test
bin/quality-report.sh | head -3
mvn -pl app verify
git add -A
git commit -m "fix: close streams and readers on every path

17 sites leaked a stream when the body threw, since close() sat after the
work rather than in a finally. Converted to try-with-resources, except
where the resource belongs to the caller -- those are suppressed with the
ownership reason stated.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

### Task 9: Collection contracts — 15 sites, each needing its callers read

Rule: `ReturnEmptyCollectionRatherThanNull` 15. Known sites include `business/jsonld/EntryJsonLd.java:185` and `:208`, `business/shortcodes/FaqBlocks.java:108` and `:120`.

**This is an API-contract change, so it is the one batch where a blind edit is actively dangerous.** A caller with `if (list != null)` becomes dead code that still compiles; a caller with `if (list == null) { fallback(); }` **silently stops running its fallback**.

- [ ] **Step 1: Enumerate and map callers**

```bash
bin/quality-report.sh ReturnEmptyCollectionRatherThanNull
```

For each method, find every caller before changing it:

```bash
grep -rn "\.methodName(" app/src/main/java app/src/test/java \
    app/src/main/webapp/WEB-INF/velocity app/src/main/webapp/themes
```

The template trees are in that grep deliberately: `#if($model.thing)` behaves differently for null than for an empty list in Velocity, so a template can depend on this too.

- [ ] **Step 2: Write a failing test per changed method**

```java
@Test
void returnsAnEmptyListRatherThanNullWhenTheEntryHasNoFaqBlocks() {
    // FaqBlocks.parse returned null for input with no [q]/[a] pairs, forcing
    // every caller to null-check a collection.
    assertEquals(List.of(), FaqBlocks.parse("no faq markup here"));
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
mvn -pl app test -Dtest=FaqBlocksTest
```
Expected: FAIL — `expected: <[]> but was: <null>`.

- [ ] **Step 4: Change the return and remove the callers' now-dead null checks**

```java
// before
if (blocks.isEmpty()) { return null; }
return blocks;
// after
return blocks;   // already empty when there is nothing to return
```

Then delete each caller's `!= null` guard — leaving them is harmless at runtime but leaves a reader unsure which contract holds.

- [ ] **Step 5: Run, lower ceilings, verify, commit**

```bash
mvn -pl app test
bin/quality-report.sh | head -3
mvn -pl app verify
git add -A
git commit -m "refactor: return empty collections instead of null

15 methods returned null for 'nothing', so every caller carried a null
check on a collection. Each call site was read before the change: a
caller with an else-branch on null would silently stop running it. The
Velocity trees were grepped too -- #if(\$x) distinguishes null from empty.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

### Task 10: Concurrency and singletons — 20 sites

Rules: `AT_STALE_THREAD_WRITE_OF_PRIMITIVE` 5, `IS2_INCONSISTENT_SYNC` 6, `SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR` 6, `SING_SINGLETON_IMPLEMENTS_SERIALIZABLE` 2, `NonThreadSafeSingleton` 1, `LI_LAZY_INIT_STATIC` 1.

Known sites: `ScheduledEntriesTask:113` + `:123`, `TrashPurgeTask:126` + `:136`, `LuceneIndexManager:130` + `:402`, `LRUCacheImpl:135-139`, `SiteWideCache:91`, `WeblogPageCache:63`, `WeblogFeedCache:63`, `IPBanList:61`, `ShortcodeExpander:122`.

- [ ] **Step 1: Enumerate**

```bash
for R in AT_STALE_THREAD_WRITE_OF_PRIMITIVE IS2_INCONSISTENT_SYNC \
         SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR SING_SINGLETON_IMPLEMENTS_SERIALIZABLE \
         NonThreadSafeSingleton LI_LAZY_INIT_STATIC; do
    echo "=== $R ==="; bin/quality-report.sh "$R"
done
```

- [ ] **Step 2: Fix the singleton constructors — mechanical and safe**

```java
// before
public SiteWideCache() { ... }
// after
private SiteWideCache() { ... }
```

Check for callers first; a singleton with a public constructor is usually only called by its own `getInstance()`, but a test may construct one directly:

```bash
grep -rn "new SiteWideCache(" app/src
```

- [ ] **Step 3: Fix `AT_STALE_THREAD_WRITE_OF_PRIMITIVE` — a real visibility bug**

A background task writes a primitive that another thread reads without synchronisation, so the reader may never observe the write:

```java
// before
private boolean interrupted = false;
// after -- note this is exactly why PMD's AvoidUsingVolatile is excluded
private volatile boolean interrupted = false;
```

- [ ] **Step 4: Fix `IS2_INCONSISTENT_SYNC` in `LRUCacheImpl` with care**

Five findings on one class: some accesses hold the lock and some do not. Read the whole class before changing anything, and prefer making the unsynchronised accesses consistent with the majority over removing synchronisation. This is a render cache on the hot path — if the right fix is not obvious from reading it, suppress with the reason and open a follow-up rather than guessing at a concurrency change.

- [ ] **Step 5: Run, lower ceilings, verify, commit**

```bash
mvn -pl app test
bin/quality-report.sh | head -3
mvn -pl app verify
git add -A
git commit -m "fix: memory visibility on task cancellation flags, private singleton ctors

The scheduled-task and index-manager cancellation flags were plain
primitives written from one thread and read from another, so a reader was
not guaranteed to observe cancellation at all. Now volatile.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

### Task 11: The two security findings — one fix, one justified suppression

**These are the findings the whole wave exists to surface. Handle them individually and do not batch them with anything else.**

**Finding 1 — `HRS_REQUEST_PARAMETER_TO_HTTP_HEADER` at `ui/rendering/util/ModDateHeaderUtil.java:96`.** The `If-Modified-Since` **request** header is echoed straight into the `Last-Modified` **response** header:

```java
// use the same date we sent when we created the ETag the first time through
response.setHeader("Last-Modified", request.getHeader("If-Modified-Since"));
```

Untrusted input reaching a response header is the response-splitting shape. Modern Tomcat rejects CR/LF in header values, so this is defence in depth rather than a live exploit — but echoing a client string as a date header is also just wrong: the correct value is the resource's own last-modified time, which the caller already has.

**Finding 2 — `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` at `business/startup/SQLScriptRunner.runScript:172`.** **This is not a bug and must not be "fixed".** `SQLScriptRunner` exists to execute SQL scripts from `bin/db/migrations`; non-constant SQL is its entire purpose, the input is a file shipped in the artifact, and no user input reaches it. It gets a suppression with that reasoning stated. An implementer who parameterises this has broken the migration runner.

- [ ] **Step 1: Write the failing test for the response header**

```java
package org.apache.roller.weblogger.ui.rendering.util;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The Last-Modified response header must be derived from the resource, never
 * echoed from the client's If-Modified-Since request header. Echoing client
 * input into a response header is the response-splitting shape, and the value
 * is wrong on its own terms besides.
 */
class ModDateHeaderUtilTest {

    @Test
    void lastModifiedIsNotEchoedFromTheRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String clientSupplied = "Thu, 01 Jan 1970 00:00:00 GMT";
        request.addHeader("If-Modified-Since", clientSupplied);
        MockHttpServletResponse response = new MockHttpServletResponse();

        long resourceLastModified = 1_600_000_000_000L;
        ModDateHeaderUtil.respondIfNotModified(request, response, resourceLastModified, null);

        assertNotEquals(clientSupplied, response.getHeader("Last-Modified"),
                "Last-Modified must come from the resource, not from the client");
    }
}
```

Read `ModDateHeaderUtil`'s real method name and parameter list first and match them.

- [ ] **Step 2: Run it and watch it fail**

```bash
mvn -pl app test -Dtest=ModDateHeaderUtilTest
```
Expected: FAIL — the header equals the client-supplied string.

- [ ] **Step 3: Fix it**

```java
// after -- the resource's own timestamp, formatted server-side
response.setDateHeader("Last-Modified", lastModified);
```

- [ ] **Step 4: Suppress the SQL finding with its reasoning**

```java
@SuppressFBWarnings(
        value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
        justification = "SQLScriptRunner exists to execute the migration scripts under "
                + "bin/db/migrations, which ship inside the artifact. Non-constant SQL is "
                + "the class's entire purpose and no user input reaches it. Parameterising "
                + "this would break the migration runner.")
```

This needs `spotbugs-annotations` at `provided` scope in `app/pom.xml`:

```xml
<dependency>
    <groupId>com.github.spotbugs</groupId>
    <artifactId>spotbugs-annotations</artifactId>
    <version>4.9.8</version>
    <scope>provided</scope>
    <optional>true</optional>
</dependency>
```

- [ ] **Step 5: Run the test and watch it pass**

```bash
mvn -pl app test -Dtest=ModDateHeaderUtilTest
```
Expected: PASS.

- [ ] **Step 6: Confirm conditional-GET still works end to end**

This touches the 304 path, which browser ITs exercise:

```bash
mvn -pl app test -Dtest='*Rendering*,*PageServlet*'
```
Expected: PASS.

- [ ] **Step 7: Lower ceilings, verify, commit**

```bash
bin/quality-report.sh | head -3
mvn -pl app verify
git add -A
git commit -m "fix: derive Last-Modified from the resource, not the client's request header

ModDateHeaderUtil echoed the client's If-Modified-Since straight back as
Last-Modified. Tomcat rejects CR/LF in header values so this was not a
live split, but echoing client input into a response header is the wrong
shape and the wrong value -- the resource's own timestamp is what belongs
there.

SQLScriptRunner's non-constant SQL is suppressed rather than fixed:
executing migration scripts is what the class is for.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

### Task 12: The structural remainder

Whatever `bin/quality-report.sh` still lists. Expected: `SimplifyBooleanReturns` 10, `ForLoopCanBeForeach` 10, `UseUtilityClass` 8, `LooseCoupling` 8, `DanglingJavadoc` 6, `ClassWithOnlyPrivateConstructorsShouldBeFinal` 4, `BC_UNCONFIRMED_CAST` 15, `EQ_COMPARETO_USE_OBJECT_EQUALS` 4, `NM_CONFUSING` 3, plus assorted singles.

- [ ] **Step 1: Enumerate everything that is left**

```bash
bin/quality-report.sh
```

- [ ] **Step 2: Apply the mechanical ones**

```java
// SimplifyBooleanReturns
if (x > 3) { return true; } else { return false; }   // before
return x > 3;                                         // after

// UseUtilityClass -- ISO8601DateParser, RegexUtil, UIUtils, ModelLoader
public final class RegexUtil {
    private RegexUtil() { }   // add; the class is all-static
}

// LooseCoupling
private ArrayList<String> names;   // before
private List<String> names;        // after
```

- [ ] **Step 3: Handle `EQ_COMPARETO_USE_OBJECT_EQUALS` deliberately**

Four themes (`SharedTheme`, `SharedThemeResourceFromDir`, `WeblogCustomTheme`, `WeblogSharedTheme`) define `compareTo` without `equals`, so `a.compareTo(b) == 0` disagrees with `a.equals(b)`. That breaks `TreeSet`/`TreeMap` in ways that surface as missing themes, not as exceptions. Either implement `equals`/`hashCode` consistently with `compareTo`, or — if these are only ever sorted in a `List` — suppress with that reason after confirming with:

```bash
grep -rn "TreeSet\|TreeMap\|SortedSet" app/src/main/java | grep -i theme
```

- [ ] **Step 4: Handle `BC_UNCONFIRMED_CAST` case by case**

An unchecked downcast that is genuinely guaranteed by the framework contract gets a suppression naming the guarantee; one that is merely *probably* fine gets an `instanceof` check.

- [ ] **Step 5: Run, lower ceilings, verify, commit**

```bash
mvn -pl app test
bin/quality-report.sh | head -3
mvn -pl app verify
git add -A
git commit -m "refactor: clear the remaining PMD and SpotBugs findings

Boolean-return simplification, utility-class constructors, interface
types on fields, and case-by-case handling of unconfirmed casts and
compareTo/equals inconsistency.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

### Task 13: Set the gate to absolute zero and document it

**Files:**
- Modify: `pom.xml` (delete the ceiling properties)
- Modify: `app/pom.xml` (delete the `maxAllowedViolations` elements)
- Modify: `app/src/test/java/.../QualityGatePomTest.java` (add the no-scaffolding assertion)
- Modify: `CLAUDE.md`

- [ ] **Step 1: Confirm the tree is actually at zero**

```bash
bin/quality-report.sh
```
Expected: `=== PMD: 0 ===`, `=== SpotBugs: 0 ===`, `=== CPD @200: 0 ===`.

If anything remains, it belongs to an earlier task — finish that task rather than raising an exclusion here. Adding an eighth exclusion is a spec change.

- [ ] **Step 2: Add the failing assertion that the scaffolding is gone**

Add to `QualityGatePomTest`:

```java
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
```

- [ ] **Step 3: Run it and watch it fail**

```bash
mvn -pl app test -Dtest=QualityGatePomTest
```
Expected: FAIL — the properties are still there.

- [ ] **Step 4: Delete the scaffolding**

Remove `<pmd.max.violations>` and `<spotbugs.max.violations>` from the parent `pom.xml`, and both `<maxAllowedViolations>` elements from `app/pom.xml`.

- [ ] **Step 5: Run it and watch it pass**

```bash
mvn -pl app test -Dtest=QualityGatePomTest
```
Expected: PASS, 5 tests.

- [ ] **Step 6: Prove the gate fails on a seeded violation — all three tools**

This is the acceptance criterion that matters. Seed one violation per tool, confirm the failure, revert.

```bash
# PMD: an unused import
sed -i '0,/^import /s//import java.util.StringJoiner;\nimport /' \
    app/src/main/java/org/apache/roller/weblogger/util/Utilities.java
mvn -pl app verify -DskipTests    # expect BUILD FAILURE naming UnnecessaryImport
git checkout app/src/main/java/org/apache/roller/weblogger/util/Utilities.java
```

Then seed a SpotBugs finding (a `String.getBytes()` with no charset in any main class), confirm `BUILD FAILURE` naming `DM_DEFAULT_ENCODING`, and revert. Then seed a CPD block by copying a 200-token method into a second class, confirm the failure, and revert.

```bash
git status --porcelain   # must be empty before continuing
```

- [ ] **Step 7: Confirm the build-time cost**

```bash
time mvn -pl app verify -DskipTests
```
Expected: the three checks add under 30 seconds (measured: PMD ~3s, CPD ~2s, SpotBugs ~11s).

- [ ] **Step 8: Document the policy in CLAUDE.md**

Add a `## Static-analysis gates` section after `### Coverage gates`:

```markdown
### Static-analysis gates

PMD, CPD and SpotBugs run at `verify` in the `app` module and fail the build
on **any** violation. Config: `config/pmd/ruleset.xml`,
`config/spotbugs/exclude.xml`; wiring in the parent `pluginManagement`,
executions in `app/pom.xml`. `bin/quality-report.sh` prints current counts and
sites; `bin/quality-report.sh <RuleName>` lists one rule's sites.

Zero tolerance is only affordable because the rule set is narrow, and it is
narrow on a stated principle: **a rule is excluded only when violating it is
systematically not a defect in this architecture**, never because there are a
lot of them. `UnnecessaryConstructor` is the clearest case — JPA entities are
required to declare a no-arg constructor, so the rule is wrong here, not noisy.
PMD's `codestyle` category (7,997 violations of pure format opinion) is not
used at all.

Seven PMD rules and three SpotBugs families are excluded, each with a reason
comment in the config file. **`QualityGatePomTest` fails the build if an
exclusion is added without a justification comment, or if the excluded set
differs from the list the test names** — so silencing a rule is never a quiet
act, and widening the exclusions is a spec change rather than an
implementation decision.

Two exclusions are **deferred, not permanent**: `GuardLogStatement` (368) and
`ProperLogger` (167) both fire on the commons-logging idiom — 176 files on
`org.apache.commons.logging` with 377 string-concatenating calls and zero
parameterized ones, plus 43 hand-written `isDebugEnabled` guards. Runtime
behaviour is already SLF4J (`jcl-over-slf4j` bridges it), so migrating is
mechanical; finishing it deletes both exclusions and re-gates at zero.

CPD runs at **200 tokens**, not lower, deliberately: at 100 it flags the three
render caches, and collapsing those into a shared base would be a behavioural
change — `WeblogPageCache` has no CacheHandler and expires only against
`weblog.lastModified` while its siblings are invalidated through
`CacheManager`. Those blocks carry `CPD-OFF` markers stating that reason.

> **Correction (2026-08-21).** As above: `WeblogFeedCache` has no CacheHandler
> either, so "its siblings" is wrong — only `SiteWideCache` is invalidated
> through `CacheManager`. See `CLAUDE.md`, Templates, for the corrected split
> and the test that now enforces it.

One-off suppressions go at the call site (`@SuppressWarnings("PMD.Rule")`,
`@SuppressFBWarnings`, `// CPD-OFF`) **with a reason**; the two config files
are for whole families only. A suppression whose justification only restates
the rule name is not a justification.
```

- [ ] **Step 9: Full verification**

```bash
mvn clean install
```
Expected: BUILD SUCCESS — unit suite, JaCoCo floors, and all three new gates.

```bash
mvn -pl app jacoco:report && bin/check-diff-coverage.sh master
```
Expected: the diff-coverage gate passes on the wave's changed lines.

- [ ] **Step 10: Browser ITs at both context paths**

Required by acceptance criterion 4: this wave touched the pagers, `ModDateHeaderUtil`'s 304 path, and charset handling, all of which are rendering-side.

```bash
mvn verify -Pit
mvn verify -Pit -Dit.context.path=roller
```
Expected: both green. ~16 minutes each; run them sequentially, never concurrently.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "build: set the static-analysis gate to zero tolerance

The tree is at 0 PMD / 0 SpotBugs / 0 CPD, so the temporary ceiling that
carried the wave is deleted and the gate is absolute. Verified by seeding
one violation per tool and watching the build fail for each.

QualityGatePomTest now also fails if the scaffolding reappears.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WFocXbEdrhUGYXBRy9CwKD"
```

---

## Verification summary

| Acceptance criterion (spec) | Verified by |
|---|---|
| 1 — zero on all three gates | Task 13 Step 1, Step 9 |
| 2 — a seeded violation fails the build, naming file/line/rule | Task 13 Step 6, all three tools |
| 3 — `QualityGatePomTest` pins wiring and exclusion policy | Task 1 Steps 1–8, Task 13 Steps 2–5 |
| 4 — suite, diff coverage, and ITs at both context paths unaffected | Task 13 Steps 9–10 |
| 5 — under 30s added | Task 13 Step 7 |
| 6 — CLAUDE.md states the policy | Task 13 Step 8 |

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
package org.apache.roller.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the CI side of the browser-IT harness: how many browsers the nightly
 * is allowed to run at once, and whether a failing nightly leaves behind
 * enough evidence to diagnose itself.
 *
 * <p>Both were learned the hard way. {@code junit-platform.properties} sets
 * class-parallelism to 4, a value chosen for -- and documented against -- a
 * 16-core developer machine, where "each worker holds a Chrome, so the
 * binding constraint is memory". A GitHub-hosted runner is not that machine,
 * and it inherited the number silently. When the runner image moved from
 * ubuntu24/20260810.271 to 20260816.277 the nightly began failing every
 * night with Chrome renderer timeouts scattered across a different handful
 * of classes each run -- several reported with a NEGATIVE remaining
 * deadline, i.e. the command's timeout had already expired before the
 * browser was scheduled at all, which is starvation rather than a slow page.
 * The parallelism must therefore be overridable per environment, and CI must
 * actually take a lower value rather than inheriting the local default.
 *
 * <p>The second test exists because of what that outage cost. The app log
 * carrying the answer -- {@code it-work/app-<run id>.log} -- lives only on
 * the runner and dies with it, so six consecutive red nightlies produced no
 * evidence anyone could read afterwards. That is also why a separate,
 * genuinely code-shaped failure on the same job (a 403 on the admin
 * {@code createUser!save.rol} POST, CLAUDE.md's "MECHANISM NOT ESTABLISHED")
 * has stayed unexplained: nothing survives the run that could explain it.
 */
class ItCiWorkflowTest {

    private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path MAIN_WORKFLOW = REPO_ROOT.resolve(".github/workflows/main.yml");
    private static final Path IT_POM = REPO_ROOT.resolve("it-selenium/pom.xml");

    /**
     * The local default and the CI value must both exist, and CI's must be
     * lower. Asserting the relationship rather than the literal 2 keeps this
     * from becoming a second place to edit when either number is retuned.
     */
    @Test
    void theNightlyRunsFewerBrowsersThanADeveloperMachineDoes() throws IOException {
        int local = localDefaultParallelism();
        int ci = ciParallelism();

        assertTrue(ci < local,
                "the nightly must lower it.parallelism below the local default (" + local
                        + "), got " + ci + ". The default is tuned for a 16-core box; a "
                        + "GitHub runner starves at that many concurrent Chromes.");
        assertTrue(ci >= 1, "parallelism must be at least 1, got " + ci);
    }

    /**
     * The pom must hand the value to JUnit as a system property on the
     * failsafe-forked JVM. junit-platform.properties alone is not
     * overridable, and a system property set on the Maven command line does
     * not reach a forked JVM by itself.
     */
    @Test
    void theParallelismReachesTheForkedJvmAsAnOverridableProperty() throws IOException {
        String pom = Files.readString(IT_POM);

        assertTrue(pom.contains("<junit.jupiter.execution.parallel.config.fixed.parallelism>"
                        + "${it.parallelism}</junit.jupiter.execution.parallel.config.fixed.parallelism>"),
                "failsafe must pass junit.jupiter.execution.parallel.config.fixed.parallelism"
                        + " as ${it.parallelism} in systemPropertyVariables, or -Dit.parallelism"
                        + " never reaches the forked JVM and junit-platform.properties wins");
    }

    /**
     * A red nightly must leave the app log and the failsafe reports behind.
     * Without them the only record is the Selenium stack trace, which says
     * what the browser could not do and nothing about what the server did.
     */
    @Test
    void aFailingNightlyUploadsTheEvidenceNeededToDiagnoseIt() throws IOException {
        String workflow = Files.readString(MAIN_WORKFLOW);
        String itJob = integrationTestJob(workflow);

        assertTrue(itJob.contains("upload-artifact"),
                "the integration-test job must upload its diagnostics; the app log lives only "
                        + "on the runner and dies with it");
        assertTrue(itJob.contains("it-work"),
                "the uploaded diagnostics must include it-selenium's it-work directory, which "
                        + "holds app-<run id>.log -- the only record of what the SERVER did");
        assertTrue(itJob.contains("failsafe-reports"),
                "the uploaded diagnostics must include the failsafe reports");
        assertTrue(itJob.contains("if: always()") || itJob.contains("if: failure()"),
                "the upload must run on failure, which is the only run whose evidence matters");
    }

    // ------------------------------------------------------------------ parsing

    private static int localDefaultParallelism() throws IOException {
        String pom = Files.readString(IT_POM);
        Matcher m = Pattern.compile("<it\\.parallelism>\\s*(\\d+)\\s*</it\\.parallelism>").matcher(pom);
        if (!m.find()) {
            return fail("no <it.parallelism> property in " + IT_POM
                    + "; the parallelism must be a named property so CI can override it");
        }
        return Integer.parseInt(m.group(1));
    }

    private static int ciParallelism() throws IOException {
        String itJob = integrationTestJob(Files.readString(MAIN_WORKFLOW));
        Matcher m = Pattern.compile("-Dit\\.parallelism=(\\d+)").matcher(itJob);
        if (!m.find()) {
            return fail("the integration-test job must pass -Dit.parallelism=<n>; without it CI "
                    + "silently inherits the developer-machine default");
        }
        return Integer.parseInt(m.group(1));
    }

    /** The integration-test job's block, so an assertion cannot pass on some other job's text. */
    private static String integrationTestJob(String workflow) {
        int start = workflow.indexOf("\n  integration-test:");
        if (start < 0) {
            return fail("no integration-test job in " + MAIN_WORKFLOW);
        }
        Matcher next = Pattern.compile("\n  [a-zA-Z][a-zA-Z0-9_-]*:").matcher(workflow);
        int end = workflow.length();
        if (next.find(start + 1)) {
            end = next.start();
        }
        return workflow.substring(start, end);
    }
}

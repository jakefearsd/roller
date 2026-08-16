/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The browser IT harness must never leave a Roller app JVM running, whatever
 * killed the build. Four abort paths used to leak one permanently: the
 * readiness timeout in {@code start-app.sh} (which exited without killing what
 * it had started), an infrastructure failure anywhere in
 * {@code pre-integration-test} (which skips {@code post-integration-test},
 * where all cleanup lived), a Ctrl-C, and a single fixed pidfile that every
 * run truncated -- so run N+1 destroyed the only record of run N's leaked pid.
 *
 * <p>The replacement is identity-based rather than pidfile-based: every process
 * the harness starts carries {@code -Droller.it.run=<run id>} and
 * {@code -Droller.it.owner=<pid>@<start time>} on its command line, so it can
 * always be found without a file, and "is this process stale?" is answered by
 * asking whether the Maven build that owns it is still alive. These tests pin
 * the three mechanisms that follow from that: a {@code start-app.sh} that kills
 * what it started on every failure path, a {@code stop-app.sh} that works with
 * no pidfile at all, a pre-run sweep that reaps other runs' corpses but leaves
 * a concurrent run alone, and a supervisor that reaps when the build it belongs
 * to dies without reaching any cleanup phase.
 *
 * <p>Linux only: the assertions drive {@code ps}/{@code kill} and the harness
 * is only ever run on Linux (CI) or a developer's Linux box.
 */
@EnabledOnOs(OS.LINUX)
class ItHarnessLeakTest {

    private static final Path SCRIPTS = Paths.get("../it-selenium/src/test/script");
    private static final Path LIB = SCRIPTS.resolve("it-harness-lib.sh");
    private static final Path START_APP = SCRIPTS.resolve("start-app.sh");
    private static final Path STOP_APP = SCRIPTS.resolve("stop-app.sh");
    private static final Path SWEEP = SCRIPTS.resolve("sweep-stale.sh");
    private static final Path SUPERVISE = SCRIPTS.resolve("supervise-run.sh");

    /** Generous: these poll real process state, and a loaded CI box is slow. */
    private static final Duration REAP_BUDGET = Duration.ofSeconds(30);

    @TempDir
    private Path work;

    private final List<Process> spawned = new ArrayList<>();
    private final Map<Long, Path> output = new HashMap<>();

    @AfterEach
    void killEverythingThisTestStarted() {
        spawned.forEach(Process::destroyForcibly);
    }

    // ---------------------------------------------------------------- start

    /**
     * Defect 1: the readiness loop expired, printed the log tail and exited 1,
     * leaving the JVM it had just started running forever -- and because this
     * runs in pre-integration-test, that exit 1 aborts the build before
     * post-integration-test, so the app-stop that would have killed it never
     * runs.
     */
    @Test
    void startAppKillsTheJvmItStartedWhenReadinessTimesOut() throws Exception {
        String runId = runId("timeout");
        Result result = runStartApp(runId, "4");

        assertEquals(1, result.exit(), "readiness timeout must still fail the build:\n" + result.output());
        assertNoProcessFor(runId, "start-app.sh timed out and left its JVM running");
    }

    /** Ctrl-C reaches start-app.sh while it is still polling for readiness. */
    @Test
    void startAppKillsTheJvmItStartedWhenItIsSignalled() throws Exception {
        String runId = runId("signal");
        Process script = startStartApp(runId, "600");

        awaitProcessFor(runId, "start-app.sh never launched its child");
        script.destroy();
        assertTrue(script.waitFor(30, TimeUnit.SECONDS), "start-app.sh ignored SIGTERM");

        assertNoProcessFor(runId, "start-app.sh was signalled and left its JVM running");
    }

    // ----------------------------------------------------------------- stop

    /**
     * Defect 2: cleanup that can only work from a pidfile cannot work at all
     * once the pidfile is missing -- which is the state a leaked run leaves
     * behind, because the next run truncates it.
     */
    @Test
    void stopAppKillsByRunMarkerWhenThePidfileIsMissing() throws Exception {
        String runId = runId("nopidfile");
        long pid = spawnMarked(runId, ownerTokenOfLiveProcess());

        Path absent = work.resolve("app-" + runId + ".pid");
        Result result = run(List.of(STOP_APP.toString(), absent.toString(), runId), Map.of(), Duration.ofMinutes(1));

        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains(String.valueOf(pid)),
                "stop-app.sh must report the pid it reaped, got:\n" + result.output());
        assertNoProcessFor(runId, "stop-app.sh could not stop a run whose pidfile was gone");
    }

    // ---------------------------------------------------------------- sweep

    /**
     * The bound on accumulation: whatever an earlier run leaked, the next run
     * finds it by marker, reaps it, and says so. Silence is the actual bug --
     * build-helper reserves a free port every run, so leaked servers never
     * collide and nothing ever complained.
     */
    @Test
    void sweepReapsAnAppWhoseOwningBuildIsGoneAndSaysSo() throws Exception {
        String staleRun = runId("stale");
        long pid = spawnMarked(staleRun, ownerTokenOfDeadProcess());

        Result result = runSweep(runId("current"));

        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains(String.valueOf(pid)) && result.output().contains(staleRun),
                "the sweep must name what it reaped (pid " + pid + ", run " + staleRun + "), got:\n"
                        + result.output());
        assertNoProcessFor(staleRun, "the sweep left a stale app running");
    }

    /**
     * The other half of the same rule, and the reason staleness is decided by
     * the owning build's liveness rather than by "an IT app exists": a second
     * run on the same machine is legitimate and must survive the first one's
     * sweep. Random reserved ports exist precisely so concurrent runs work.
     */
    @Test
    void sweepLeavesAConcurrentRunAlone() throws Exception {
        Process owner = spawn(List.of("sleep", "300"));
        String liveRun = runId("live");
        long pid = spawnMarked(liveRun, ownerToken(owner.pid()));

        Result result = runSweep(runId("current"));

        assertEquals(0, result.exit(), result.output());
        assertTrue(isAlive(pid), "the sweep killed a concurrent run's app:\n" + result.output());
        assertFalse(result.output().contains(String.valueOf(pid)),
                "the sweep reported a live run as reaped:\n" + result.output());
    }

    // ----------------------------------------------------------- supervisor

    /**
     * Defect 3: cleanup cannot live only in post-integration-test, because an
     * infrastructure failure earlier in pre-integration-test (pg-wait-ready,
     * migrate.sh, the seed) aborts the build before that phase is ever
     * reached, and Ctrl-C skips it too. The supervisor is started before any
     * of that and outlives the build, so cleanup no longer depends on reaching
     * a later phase.
     */
    @Test
    void supervisorReapsTheRunWhenTheOwningBuildDies() throws Exception {
        Process owner = spawn(List.of("sleep", "300"));
        String runId = runId("supervised");
        String ownerToken = ownerToken(owner.pid());
        long app = spawnMarked(runId, ownerToken);

        Map<String, String> env = new HashMap<>();
        env.put("IT_OWNER_STAMP", ownerToken);
        Instant before = Instant.now();
        Result result = run(List.of(SUPERVISE.toString(), runId, "roller-it-postgres-" + runId, work.toString()),
                env, Duration.ofSeconds(30));

        assertEquals(0, result.exit(), result.output());
        assertTrue(Duration.between(before, Instant.now()).toSeconds() < 15,
                "supervise-run.sh must detach and return immediately, not block the build");
        assertTrue(isAlive(app), "the supervisor reaped a run whose build is still alive");

        owner.destroyForcibly();
        assertTrue(owner.waitFor(30, TimeUnit.SECONDS), "the fake build did not die");

        assertNoProcessFor(runId, "the supervisor did not reap the run after its build died");
        assertNoSupervisorFor(runId, "the supervisor outlived the run it was cleaning up");
    }

    /**
     * A supervisor marked as a member of its own run finds its own transient
     * subshells while enumerating what to kill -- a forked shell shows its
     * parent's command line in {@code ps} -- and kills those instead of, and
     * potentially as well as, the app. So the run marker and the supervisor
     * marker are two different properties, and a supervisor with nothing to
     * clean up must report exactly that.
     */
    @Test
    void supervisorNeverMistakesItsOwnSubshellsForTheRun() throws Exception {
        Process owner = spawn(List.of("sleep", "300"));
        String runId = runId("selfkill");
        String ownerToken = ownerToken(owner.pid());

        Map<String, String> env = new HashMap<>();
        env.put("IT_OWNER_STAMP", ownerToken);
        env.put("IT_POLL_SECONDS", "1");
        Result start = run(List.of(SUPERVISE.toString(), runId, "roller-it-postgres-" + runId, work.toString()),
                env, Duration.ofSeconds(30));
        assertEquals(0, start.exit(), start.output());

        owner.destroyForcibly();
        assertTrue(owner.waitFor(30, TimeUnit.SECONDS), "the fake build did not die");

        String log = awaitFileContaining(work.resolve("supervisor-" + runId + ".log"), "cleaned up");
        assertFalse(log.contains("killing pid"),
                "the supervisor had nothing to reap but killed something anyway:\n" + log);
    }

    // --------------------------------------------------------------- driving

    private Result runStartApp(String runId, String timeoutSeconds) throws Exception {
        Process script = startStartApp(runId, timeoutSeconds);
        return await(script, Duration.ofMinutes(2));
    }

    private Process startStartApp(String runId, String timeoutSeconds) throws Exception {
        Path war = Files.createFile(work.resolve(runId + ".war"));
        Path props = Files.createFile(work.resolve(runId + ".properties"));
        Map<String, String> env = new HashMap<>();
        env.put("IT_APP_JAVA", fakeJava().toString());
        env.put("IT_READY_TIMEOUT_SECONDS", timeoutSeconds);
        return spawn(List.of(START_APP.toString(),
                war.toString(),
                String.valueOf(freePort()),
                props.toString(),
                work.resolve("app-" + runId + ".pid").toString(),
                work.resolve("app-" + runId + ".log").toString(),
                runId), env);
    }

    private Result runSweep(String currentRunId) throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("IT_WORK_DIR", work.toString());
        return run(List.of(SWEEP.toString(), currentRunId), env, Duration.ofMinutes(1));
    }

    /**
     * Stands in for {@code java -jar roller.war}: keeps the command line it was
     * given (so the run marker stays greppable, exactly as the real JVM's does)
     * and never answers on the port, so readiness always times out.
     */
    private Path fakeJava() throws IOException {
        Path java = work.resolve("fake-java.sh");
        if (!Files.exists(java)) {
            Files.writeString(java, "#!/usr/bin/env bash\nwhile :; do sleep 1; done\n", StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
        }
        return java;
    }

    /** A process carrying the harness's markers, standing in for a Roller app JVM. */
    private long spawnMarked(String runId, String ownerToken) throws Exception {
        Process process = spawn(List.of("bash", "-c", "while :; do sleep 1; done",
                "-Droller.it.run=" + runId, "-Droller.it.owner=" + ownerToken));
        awaitProcessFor(runId, "the fixture process never appeared");
        return process.pid();
    }

    /** Asks the harness's own library for the token, so the format has one definition. */
    private String ownerToken(long pid) throws Exception {
        Result result = run(List.of("bash", "-c", "source \"$1\"; it_owner_stamp \"$2\"",
                "bash", LIB.toString(), String.valueOf(pid)), Map.of(), Duration.ofSeconds(30));
        assertEquals(0, result.exit(), "it_owner_stamp failed:\n" + result.output());
        String token = result.output().trim();
        assertFalse(token.isEmpty(), "it_owner_stamp produced nothing for pid " + pid);
        return token;
    }

    private String ownerTokenOfLiveProcess() throws Exception {
        return ownerToken(ProcessHandle.current().pid());
    }

    private String ownerTokenOfDeadProcess() throws Exception {
        Process shortLived = spawn(List.of("sleep", "5"));
        String token = ownerToken(shortLived.pid());
        shortLived.destroyForcibly();
        assertTrue(shortLived.waitFor(30, TimeUnit.SECONDS), "fixture process would not die");
        return token;
    }

    // --------------------------------------------------------------- process

    private record Result(int exit, String output) {
    }

    private Process spawn(List<String> command) throws IOException {
        return spawn(command, Map.of());
    }

    /**
     * Output goes to a file rather than a pipe: draining a pipe before
     * {@code waitFor} would block for as long as the process ran, which would
     * silently defeat every timeout in this class.
     */
    private Process spawn(List<String> command, Map<String, String> env) throws IOException {
        Path log = work.resolve("proc-" + spawned.size() + ".out");
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().putAll(env);
        Process process = builder.start();
        spawned.add(process);
        output.put(process.pid(), log);
        return process;
    }

    private Result run(List<String> command, Map<String, String> env, Duration timeout) throws Exception {
        return await(spawn(command, env), timeout);
    }

    private Result await(Process process, Duration timeout) throws Exception {
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("timed out after " + timeout + "; output so far:\n" + outputOf(process));
        }
        return new Result(process.exitValue(), outputOf(process));
    }

    private String outputOf(Process process) throws IOException {
        Path log = output.get(process.pid());
        return log != null && Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Every process whose command line carries the given marker, whoever started it. */
    private static List<String> processesMarked(String property, String runId) throws Exception {
        Process ps = new ProcessBuilder("ps", "-ww", "-eo", "pid=,args=").redirectErrorStream(true).start();
        String output = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        ps.waitFor(30, TimeUnit.SECONDS);
        return output.lines()
                .filter(line -> line.contains(property + runId))
                .toList();
    }

    private static List<String> processesFor(String runId) throws Exception {
        return processesMarked("-Droller.it.run=", runId);
    }

    private static String awaitFileContaining(Path file, String needle) throws Exception {
        Instant deadline = Instant.now().plus(REAP_BUDGET);
        String content = "";
        while (Instant.now().isBefore(deadline)) {
            content = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            if (content.contains(needle)) {
                return content;
            }
            Thread.sleep(200);
        }
        return fail(file + " never contained '" + needle + "'; it holds:\n" + content);
    }

    private static void awaitProcessFor(String runId, String message) throws Exception {
        Instant deadline = Instant.now().plus(REAP_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            if (!processesFor(runId).isEmpty()) {
                return;
            }
            Thread.sleep(200);
        }
        fail(message + " (no process carrying -Droller.it.run=" + runId + ")");
    }

    private static void assertNoProcessFor(String runId, String message) throws Exception {
        assertNoProcessMarked("-Droller.it.run=", runId, message);
    }

    private static void assertNoSupervisorFor(String runId, String message) throws Exception {
        assertNoProcessMarked("-Droller.it.supervisor=", runId, message);
    }

    private static void assertNoProcessMarked(String property, String runId, String message) throws Exception {
        Instant deadline = Instant.now().plus(REAP_BUDGET);
        List<String> survivors = List.of();
        while (Instant.now().isBefore(deadline)) {
            survivors = processesMarked(property, runId);
            if (survivors.isEmpty()) {
                return;
            }
            Thread.sleep(500);
        }
        fail(message + "; still running:\n" + String.join("\n", survivors));
    }

    private static String runId(String label) {
        return "itleaktest-" + label + "-" + System.nanoTime();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

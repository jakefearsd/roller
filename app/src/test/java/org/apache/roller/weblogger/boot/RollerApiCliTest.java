package org.apache.roller.weblogger.boot;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The CLI is shell, so this pins the properties that would otherwise only
 * fail in someone's terminal: it must not store a password, and it must
 * refuse to run without the tools it shells out to.
 */
class RollerApiCliTest {

    private static String cli() throws Exception {
        Path path = Path.of("..", "bin", "roller-api");
        if (!Files.exists(path)) {
            path = Path.of("bin", "roller-api");
        }
        assertTrue(Files.exists(path), "bin/roller-api must exist");
        return Files.readString(path);
    }

    @Test
    void itFailsFastOnErrorsAndUnsetVariables() throws Exception {
        assertTrue(cli().contains("set -euo pipefail"),
                "a partial run against a live blog is worse than no run");
    }

    @Test
    void thePasswordIsReadSilentlyAndNeverWrittenToTheCredentialsFile() throws Exception {
        String script = cli();
        assertTrue(script.contains("read -rs"), "the password must not echo");
        assertFalse(script.contains("password=") && script.contains(">> \"$CRED_FILE\""),
                "only the token is ever persisted");
    }

    @Test
    void theCredentialsFileIsCreatedPrivate() throws Exception {
        assertTrue(cli().contains("chmod 600"),
                "a token in a world-readable file is a leaked credential");
    }

    @Test
    void itChecksForCurlAndJqBeforeDoingAnything() throws Exception {
        String script = cli();
        assertTrue(script.contains("command -v curl"));
        assertTrue(script.contains("command -v jq"));
    }

    /**
     * {@code curl -u user:password} puts the password on THIS PROCESS'S OWN
     * command line -- readable by any other local user via {@code ps -ef} or
     * {@code /proc/<pid>/cmdline} for the life of the request. The mint call
     * must authenticate via a {@code --netrc-file} instead, never {@code -u},
     * under any condition -- there is no fallback to pin against, so this
     * asserts the absence of the leaking form and the presence of the safe
     * one.
     */
    @Test
    void thePasswordNeverReachesAProcessCommandLine() throws Exception {
        String script = cli();
        assertFalse(script.contains("-u \"${user}"),
                "curl -u puts the password on the process command line, visible via ps/proc");
        assertFalse(script.contains("-u \"${user}:${password}\""),
                "curl -u puts the password on the process command line, visible via ps/proc");
        assertTrue(script.contains("--netrc-file"),
                "the mint request must authenticate via a netrc file, not -u");
    }

    /**
     * Regression coverage for round 1's other three fixes -- none of them
     * had a test of their own, so reverting any one would pass every other
     * test in this class undetected. Text assertions are weaker than
     * functional ones, but they are what this file's harness does, and they
     * are strictly better than nothing.
     */
    @Test
    void deleteAndTrashCallsNoLongerDiscardTheErrorBody() throws Exception {
        String script = cli();
        for (String line : script.split("\n", -1)) {
            if (line.contains("-o /dev/null")) {
                assertTrue(line.trim().startsWith("#"),
                        "a live curl invocation must not discard its body with -o /dev/null "
                                + "(a 409's `detail` is exactly what --fail-with-body exists to surface): "
                                + line);
            }
        }
    }

    @Test
    void mediaUploadsStatusCaptureIsGuardedAgainstSetE() throws Exception {
        String script = cli();
        assertTrue(script.contains("if ! status=\"$(api_raw"),
                "media_upload's status capture must be guarded (an `if` condition) so a non-2xx "
                        + "response cannot trip set -e before the body is read, printed and cleaned up");
    }

    @Test
    void mainAcceptsGlobalUrlAndTokenFlags() throws Exception {
        String script = cli();
        assertTrue(script.contains("--url|--token)"),
                "--url/--token must be parsed as global flags before the subcommand, "
                        + "matching the precedence documented in the file header");
    }

    /**
     * The twin of {@link #mediaUploadsStatusCaptureIsGuardedAgainstSetE()}:
     * {@code cmd_auth_login}'s token extraction had the identical unguarded
     * bare-pipe-into-assignment shape (a login failure's problem+json body
     * was captured but never read, because set -e aborted on the pipeline's
     * nonzero exit before `die` -- or even `rm -f` -- ever ran), and it sat
     * a few lines from the pattern this file already knew to guard against.
     */
    @Test
    void loginsResponseCaptureIsGuardedAgainstSetE() throws Exception {
        String script = cli();
        assertTrue(script.contains("if ! login_response=\"$(curl"),
                "cmd_auth_login's response capture must be guarded so a login failure "
                        + "cannot silently abort the script before `die` ever runs");
        for (String line : script.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue; // explanatory comments are allowed to quote the old, fixed shape
            }
            assertFalse(line.contains("token=$(curl"),
                    "the token must never be extracted via a bare, unguarded curl | jq pipe assignment: "
                            + line);
        }
    }

    /**
     * A caller who forgets the value entirely --
     * {@code roller-api --url auth login --weblog h} -- must not have
     * ROLLER_API_URL silently set to "auth" with "login" then misread as the
     * top-level command.
     */
    @Test
    void globalUrlAndTokenFlagsRejectAKnownCommandNameAsTheirValue() throws Exception {
        String script = cli();
        assertTrue(script.contains("auth|entries|media|audit|admin)"),
                "a --url/--token flag with no value must not silently swallow the next command name");
    }
}

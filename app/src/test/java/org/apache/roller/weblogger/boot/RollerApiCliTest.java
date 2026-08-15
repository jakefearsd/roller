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

    /**
     * Whole-branch review, Must Fix 6: the original second conjunct tested
     * for {@code >> "$CRED_FILE"}, which the script never uses (it writes
     * with a plain {@code >}, see {@code theCredentialsFileIsCreatedPrivate}
     * below) -- {@code A && false} is always false, so
     * {@code assertFalse(...)} was vacuously true regardless of whether
     * {@code password=} appeared anywhere. Rewritten to actually inspect
     * every line that touches {@code $CRED_FILE} for any mention of the
     * password, which is the property the test's name and the class javadoc
     * both promise.
     */
    @Test
    void thePasswordIsReadSilentlyAndNeverWrittenToTheCredentialsFile() throws Exception {
        String script = cli();
        assertTrue(script.contains("read -rs"), "the password must not echo");
        for (String line : script.split("\n", -1)) {
            if (line.contains("$CRED_FILE")) {
                assertFalse(line.contains("password") || line.contains("$password"),
                        "a line writing to $CRED_FILE must never mention the password: " + line);
            }
        }
    }

    /**
     * Whole-branch review, Must Fix 6: {@code "chmod 600"} occurs twice in
     * the script -- once for the netrc temp file used during login, once for
     * the credentials file this test is named for -- so a plain {@code
     * contains("chmod 600")} stayed green even with the credentials-file
     * chmod deleted entirely, landing the token world-readable. Asserts the
     * chmod appears on the line immediately after the write to
     * {@code $CRED_FILE} instead of merely appearing somewhere in the file.
     */
    @Test
    void theCredentialsFileIsCreatedPrivate() throws Exception {
        String[] lines = cli().split("\n", -1);
        int writeLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("> \"$CRED_FILE\"")) {
                writeLine = i;
                break;
            }
        }
        assertTrue(writeLine >= 0, "must find the line that writes $CRED_FILE");
        assertTrue(lines[writeLine + 1].trim().startsWith("chmod 600 \"$CRED_FILE\""),
                "the line right after writing $CRED_FILE must chmod it 600 -- "
                        + "a token in a world-readable file is a leaked credential: "
                        + lines[writeLine + 1]);
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

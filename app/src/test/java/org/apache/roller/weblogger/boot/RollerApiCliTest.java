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
}

package org.apache.roller.weblogger.ui.restapi;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The docs page is the API's front door. This pins the two things a reader
 * cannot recover for themselves: that /api/v1 is unstable while Roller is
 * 0.x, and that a token is minted by the CLI rather than in the UI.
 */
class OpenApiDocumentTest {

    private static String docs() throws Exception {
        Path path = Path.of("..", "docs", "api", "README.md");
        if (!Files.exists(path)) {
            path = Path.of("docs", "api", "README.md");
        }
        assertTrue(Files.exists(path), "docs/api/README.md must exist");
        return Files.readString(path);
    }

    @Test
    void theInstabilityOfV1IsStated() throws Exception {
        String text = docs().toLowerCase();
        assertTrue(text.contains("unstable"),
                "a client author must be told v1 can change while Roller is 0.x");
    }

    @Test
    void theBootstrapPathIsDocumented() throws Exception {
        assertTrue(docs().contains("roller-api auth login"),
                "there is no UI for minting a token -- the CLI is the only route");
    }
}

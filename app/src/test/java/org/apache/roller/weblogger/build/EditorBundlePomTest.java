package org.apache.roller.weblogger.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the JavaScript build that produces the editor bundle. The bundle is
 * built by Maven (frontend-maven-plugin + esbuild) and is not committed, so
 * the only thing that can rot is this wiring -- a plugin dropped from the
 * pom builds a WAR whose editor page loads a 404 and says nothing.
 */
class EditorBundlePomTest {

    private static final Path APP_POM = Path.of("pom.xml");
    private static final Path PARENT_POM = Path.of("..", "pom.xml");
    private static final Path PACKAGE_JSON = Path.of("frontend", "package.json");
    private static final Path HEAD_JSP = Path.of("src/main/webapp/WEB-INF/jsps/tiles/head.jsp");

    @Test
    void theFrontendPluginIsPinnedInTheParentAndExecutedInApp() throws IOException {
        String parent = Files.readString(PARENT_POM);
        assertTrue(parent.contains("<artifactId>frontend-maven-plugin</artifactId>"),
                "parent pluginManagement must declare frontend-maven-plugin");
        Matcher node = Pattern.compile("<nodeVersion>v(\\d+)\\.(\\d+)\\.(\\d+)</nodeVersion>").matcher(parent);
        assertTrue(node.find(), "nodeVersion must be pinned to an exact vX.Y.Z");
        assertEquals("22", node.group(1), "Node 22 LTS");

        String app = Files.readString(APP_POM);
        int install = app.indexOf("<goal>install-node-and-npm</goal>");
        int ci = app.indexOf("<id>npm-ci</id>");
        int build = app.indexOf("<id>npm-build</id>");
        assertTrue(install > 0 && ci > install && build > ci,
                "install-node-and-npm, npm ci, npm run build must be declared in that order");
        assertTrue(app.contains("<workingDirectory>${project.basedir}/frontend</workingDirectory>"));
        assertTrue(app.contains("<id>npm-test</id>"), "the frontend node:test suite must run in the test phase");
    }

    @Test
    void theBundleLandsWhereHeadJspLoadsIt() throws IOException {
        String build = Files.readString(Path.of("frontend", "build.mjs"));
        assertTrue(build.contains("target/classes/static/roller-ui/scripts/roller-editor.js"),
                "esbuild outfile must be the classpath static path");
        String head = Files.readString(HEAD_JSP);
        assertTrue(head.contains("/roller-ui/scripts/roller-editor.js"),
                "head.jsp must load the bundle");
    }

    @Test
    @Disabled("removed in Task A3")
    void easyMdeAndFontAwesomeAreGone() throws IOException {
        String app = Files.readString(APP_POM);
        String head = Files.readString(HEAD_JSP);
        assertFalse(app.contains("easymde"), "pom still depends on easymde");
        assertFalse(app.contains("font-awesome"), "pom still depends on font-awesome");
        assertFalse(head.contains("easymde"), "head.jsp still loads easymde");
        assertFalse(head.contains("font-awesome"), "head.jsp still loads font-awesome");
    }

    @Test
    void nodeArtifactsAreIgnored() throws IOException {
        String ignore = Files.readString(Path.of("..", ".gitignore"));
        assertTrue(ignore.contains("app/frontend/node_modules/"));
        assertTrue(ignore.contains("app/frontend/node/"));
        assertTrue(Files.exists(PACKAGE_JSON.resolveSibling("package-lock.json")),
                "package-lock.json must be committed so npm ci is reproducible");
    }
}

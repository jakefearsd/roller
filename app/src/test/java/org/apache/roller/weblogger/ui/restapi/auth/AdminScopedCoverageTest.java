package org.apache.roller.weblogger.ui.restapi.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Makes the {@link AdminScoped} convention enforceable rather than merely
 * documented: any REST API controller mapped under {@code /v1/admin} must
 * carry it, or {@code ApiScopeInterceptor} never applies the ADMIN-role
 * ceiling to it and the controller is silently ungated for every scoped
 * token regardless of role. Tasks 15 and 16 add the real admin controllers
 * this wave; this is what catches them if they forget the annotation.
 */
class AdminScopedCoverageTest {

    @Test
    void everyControllerMappedUnderV1AdminCarriesAdminScoped() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Class<?> controller : restApiV1Classes()) {
            RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            boolean mapsUnderAdmin = Arrays.stream(mapping.value())
                    .anyMatch(path -> path.startsWith("/v1/admin"));
            if (mapsUnderAdmin && !controller.isAnnotationPresent(AdminScoped.class)) {
                violations.add(controller.getName());
            }
        }

        assertTrue(violations.isEmpty(),
                "These controllers are mapped under /v1/admin but do not carry @AdminScoped, "
                        + "so ApiScopeInterceptor never applies the ADMIN-role ceiling to them:\n  "
                        + String.join("\n  ", violations));
    }

    /** Every class file directly under {@code ui.restapi.v1}, found on the classpath. */
    private static List<Class<?>> restApiV1Classes() throws Exception {
        Path root = Paths.get("src/main/java/org/apache/roller/weblogger/ui/restapi/v1");
        assertTrue(Files.isDirectory(root), "Expected " + root.toAbsolutePath());

        List<Class<?>> classes = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString();
                String className = "org.apache.roller.weblogger.ui.restapi.v1."
                        + relative.substring(0, relative.length() - ".java".length())
                                .replace(java.io.File.separatorChar, '.');
                classes.add(Class.forName(className));
            }
        }
        assertTrue(!classes.isEmpty(), "No REST API v1 controllers found to scan");
        return classes;
    }
}

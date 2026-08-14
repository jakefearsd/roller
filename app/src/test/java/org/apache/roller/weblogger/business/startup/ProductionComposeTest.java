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
package org.apache.roller.weblogger.business.startup;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the production stack's shape: the deploy host holds
 * docker-compose.prod.yml and .env and nothing else (see
 * docs/superpowers/specs/2026-08-14-container-push-deployment-design.md).
 *
 * <p>Reintroducing a bind mount is the single change that would silently undo
 * that -- the stack would keep working on a developer's machine, which has the
 * checkout, and fail only on a host that does not. Same reasoning as
 * {@link UmamiViewScriptTest} policing deploy/analytics from a unit test.
 */
class ProductionComposeTest {

    private static final Path COMPOSE = Paths.get("../docker-compose.prod.yml");

    private static Map<String, Object> compose;

    @SuppressWarnings("unchecked")
    @BeforeAll
    static void load() throws IOException {
        assertTrue(Files.exists(COMPOSE), "missing " + COMPOSE.toAbsolutePath());
        try (InputStream in = Files.newInputStream(COMPOSE)) {
            compose = (Map<String, Object>) new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() {
        return (Map<String, Object>) compose.get("services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        Map<String, Object> svc = (Map<String, Object>) services().get(name);
        assertNotNull(svc, "no '" + name + "' service in " + COMPOSE);
        return svc;
    }

    @Test
    @SuppressWarnings("unchecked")
    void noServiceBindMountsARepositoryPath() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Object> entry : services().entrySet()) {
            Object volumes = ((Map<String, Object>) entry.getValue()).get("volumes");
            if (!(volumes instanceof List<?> list)) {
                continue;
            }
            for (Object volume : list) {
                String spec = String.valueOf(volume);
                String source = spec.split(":", 2)[0];
                // A named volume's source is a bare name; a bind mount's is a path.
                if (source.startsWith(".") || source.startsWith("/")) {
                    offenders.add(entry.getKey() + ": " + spec);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "the production host has no checkout, so nothing may be bind-mounted from one: "
                        + offenders);
    }

    @Test
    void noServiceBuildsFromSource() {
        for (Map.Entry<String, Object> entry : services().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> svc = (Map<String, Object>) entry.getValue();
            assertTrue(svc.get("build") == null,
                    entry.getKey() + " declares build:, but the production host has no build "
                            + "context -- images come from the registry");
        }
    }

    @Test
    void provisionRunsOnceAndGatesTheServicesThatNeedASchema() {
        assertEquals("no", String.valueOf(service("provision").get("restart")),
                "provision is a one-shot job, not a restarting service");

        for (String dependent : List.of("app", "umami", "listmonk")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dependsOn =
                    (Map<String, Object>) service(dependent).get("depends_on");
            assertNotNull(dependsOn, dependent + " must declare depends_on");
            @SuppressWarnings("unchecked")
            Map<String, Object> condition = (Map<String, Object>) dependsOn.get("provision");
            assertNotNull(condition, dependent + " must wait for provision");
            assertEquals("service_completed_successfully", condition.get("condition"),
                    dependent + " must start only after provisioning succeeds -- this is what "
                            + "replaced deploy.sh's migrate-then-start ordering");
        }
    }

    @Test
    void onlyCaddyPublishesNonLoopbackPorts() {
        for (Map.Entry<String, Object> entry : services().entrySet()) {
            @SuppressWarnings("unchecked")
            Object ports = ((Map<String, Object>) entry.getValue()).get("ports");
            if (ports == null) {
                continue;
            }
            assertEquals("caddy", entry.getKey(), "only caddy may publish ports");
            for (Object port : (List<?>) ports) {
                String spec = String.valueOf(port);
                boolean publicPort = spec.startsWith("80:") || spec.startsWith("443:");
                assertTrue(publicPort || spec.startsWith("127.0.0.1:"),
                        "only 80 and 443 may be reachable off-host; " + spec + " is neither "
                                + "those nor loopback-bound");
            }
        }
    }

    @Test
    void theAppTakesItsConfigurationFromEnvFile() {
        assertEquals(".env", String.valueOf(service("app").get("env_file")),
                "the app's whole configuration arrives as ROLLER_* variables from .env");
    }
}

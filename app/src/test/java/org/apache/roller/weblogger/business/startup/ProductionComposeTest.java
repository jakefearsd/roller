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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        // A source is a named volume only if it is declared under the file's
        // top-level `volumes:` key -- anything else is a bind mount, whether it
        // is written in short syntax ("source:target") or long syntax
        // ({type: bind, source: ..., target: ...}), and whether or not the
        // source string happens to look like a path. Keying off the
        // authoritative list here (rather than guessing from the source
        // string's shape) is what catches BOTH a long-syntax bind mount --
        // whose string form is a Map's "key=value" toString with no colon at
        // all, so a colon-split never sees it -- AND a short-syntax relative
        // path missing a leading "./", which still resolves as a bind mount to
        // Compose even though it does not start with "." or "/".
        Map<String, Object> topLevelVolumes = (Map<String, Object>) compose.get("volumes");
        Set<String> namedVolumes = topLevelVolumes == null ? Set.of() : topLevelVolumes.keySet();

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Object> entry : services().entrySet()) {
            Object volumes = ((Map<String, Object>) entry.getValue()).get("volumes");
            if (!(volumes instanceof List<?> list)) {
                continue;
            }
            for (Object volume : list) {
                String source;
                String display;
                if (volume instanceof Map<?, ?> longForm) {
                    Object src = longForm.get("source");
                    source = src == null ? "" : String.valueOf(src);
                    display = String.valueOf(longForm);
                } else {
                    String spec = String.valueOf(volume);
                    source = spec.split(":", 2)[0];
                    display = spec;
                }
                if (!source.isBlank() && !namedVolumes.contains(source)) {
                    offenders.add(entry.getKey() + ": " + display);
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
            Object dependsOnRaw = service(dependent).get("depends_on");
            assertNotNull(dependsOnRaw, dependent + " must declare depends_on");
            if (!(dependsOnRaw instanceof Map<?, ?> dependsOn)) {
                fail(dependent + "'s depends_on must be the long map form (with a condition per "
                        + "dependency), not a bare list: " + dependsOnRaw);
                return;
            }
            Object conditionRaw = dependsOn.get("provision");
            assertNotNull(conditionRaw, dependent + " must wait for provision");
            if (!(conditionRaw instanceof Map<?, ?> condition)) {
                fail(dependent + "'s depends_on.provision must be a map carrying a condition, "
                        + "not: " + conditionRaw);
                return;
            }
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
            if (!(ports instanceof List<?> portList)) {
                fail(entry.getKey() + "'s ports must be a list, not: " + ports);
                return;
            }
            for (Object port : portList) {
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

    @Test
    void analyticsViewsNeverRestarts() {
        // A restarting one-shot that waits up to ANALYTICS_VIEWS_WAIT_SECONDS
        // (default 180s) for website_event to appear would hammer the
        // database indefinitely if that table is never going to show up
        // (Umami misconfigured, or simply not deployed on this stack) --
        // restart: "no" is what makes a timeout here a one-time, logged
        // no-op instead of a permanent background retry storm.
        assertEquals("no", String.valueOf(service("analytics-views").get("restart")),
                "analytics-views must not restart -- see the comment above for why a "
                        + "restarting one-shot here is actively harmful, not just wasteful");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyEntrypointPathIsActuallyBakedIntoTheImage() throws IOException {
        // C1: deploy/backup/loop.sh called the absolute path /backup.sh, which
        // existed only under the OLD compose file's bind mounts. The image now
        // bakes the pair at /app/backup/{loop,backup}.sh, and nothing caught
        // the drift -- loop.sh's own call site was never touched by the wave
        // that moved the scripts, so no per-task review ever looked at it, and
        // this compose file's shape was pinned thoroughly while the image's
        // internal layout was pinned nowhere at all. That gap is what this
        // test closes: for every service whose entrypoint names an absolute
        // path under /app, the root Dockerfile must actually create that path
        // (a COPY destination or a chmod target) -- reading the Dockerfile as
        // plain text, the same idiom this class already uses for the compose
        // file.
        Path dockerfile = Paths.get("../Dockerfile");
        assertTrue(Files.exists(dockerfile), "missing " + dockerfile.toAbsolutePath());
        String dockerfileText = Files.readString(dockerfile, StandardCharsets.UTF_8);

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Object> entry : services().entrySet()) {
            Map<String, Object> svc = (Map<String, Object>) entry.getValue();
            Object entrypointRaw = svc.get("entrypoint");
            if (entrypointRaw == null) {
                continue;
            }
            String entrypointPath;
            if (entrypointRaw instanceof List<?> list) {
                assertFalse(list.isEmpty(), entry.getKey() + "'s entrypoint list is empty");
                entrypointPath = String.valueOf(list.get(0));
            } else {
                entrypointPath = String.valueOf(entrypointRaw).trim().split("\\s+")[0];
            }
            if (!entrypointPath.startsWith("/app/")) {
                continue;
            }
            if (!dockerfileText.contains(entrypointPath)) {
                offenders.add(entry.getKey() + ": " + entrypointPath);
            }
        }
        assertTrue(offenders.isEmpty(),
                "these services declare an /app/* entrypoint the Dockerfile never creates "
                        + "(no matching COPY destination or chmod target): " + offenders);
    }

    @Test
    void analyticsViewsGateNothing() {
        assertNotNull(services().get("analytics-views"),
                "the analytics views one-shot must exist");

        for (Map.Entry<String, Object> entry : services().entrySet()) {
            @SuppressWarnings("unchecked")
            Object dependsOn = ((Map<String, Object>) entry.getValue()).get("depends_on");
            if (dependsOn instanceof Map<?, ?> conditions) {
                assertFalse(conditions.containsKey("analytics-views"),
                        entry.getKey() + " must not wait on analytics-views: it installs a Grafana "
                                + "dashboard view, and a failure there must never keep the blog down");
            } else if (dependsOn instanceof List<?> list) {
                assertFalse(list.contains("analytics-views"),
                        entry.getKey() + " must not wait on analytics-views");
            }
        }
    }

    @Test
    void caddyDockerfileBuildsWithXcaddyAndNamesADnsProviderModule() throws IOException {
        // Per-weblog custom domains (virtual hosting) need a wildcard TLS
        // certificate, which means DNS-01 -- something stock `caddy:*` cannot
        // do at all, since DNS-01 requires a provider-specific module compiled
        // into the binary. A plain `FROM caddy` builds and runs just fine and
        // fails only at certificate-request time, in production, on a
        // wildcard that never arrives. Reading the Dockerfile as plain text
        // (the same idiom everyEntrypointPathIsActuallyBakedIntoTheImage above
        // uses on the root Dockerfile) is what makes a future revert to a
        // stock image fail the BUILD instead of failing silently later.
        Path dockerfile = Paths.get("../deploy/caddy/Dockerfile");
        assertTrue(Files.exists(dockerfile), "missing " + dockerfile.toAbsolutePath());
        String text = Files.readString(dockerfile, StandardCharsets.UTF_8);

        assertTrue(text.contains("xcaddy build"),
                "the Caddy image must be built with xcaddy -- stock caddy:* has no "
                        + "DNS-01 provider modules compiled in");
        assertTrue(text.matches("(?s).*--with\\s+github\\.com/caddy-dns/\\S+.*"),
                "the xcaddy build must name a github.com/caddy-dns/<provider> module, "
                        + "or DNS-01 has nothing to authenticate against the zone with");
        assertTrue(text.contains("AS builder") && text.contains("COPY --from=builder"),
                "the xcaddy-built binary must be copied into a separate runtime stage -- "
                        + "shipping the builder stage itself would ship Go toolchain and "
                        + "source into production");
    }
}

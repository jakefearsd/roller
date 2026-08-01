/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.roller.weblogger.boot;

import jakarta.servlet.ServletContext;

import org.apache.roller.weblogger.business.MockWeblogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Covers what's cheaply testable about {@link RollerLifecycle} without a live
 * servlet container: its declared phase, the {@code isRunning()} default, and
 * {@code stop()} being safe to call when {@code start()} never ran.
 *
 * <p>Exercising {@code start()} itself needs a real {@code ApplicationContext}
 * with the business tier wired up (a database-backed {@code WebloggerBeanConfig},
 * a real {@code DatabaseProvider}, ...), which is what the live smoke tests
 * recorded in the Task 2 report cover (both a {@code spring-boot:run} exploded
 * run and, after the Task 2b fix, {@code java -jar}) -- not appropriate for a
 * plain unit test in this suite.
 */
class RollerLifecycleTest {

    /**
     * Boot's own {@code WebServerStartStopLifecycle} phase, {@code Integer.MAX_VALUE - 2048}.
     * Verified via {@code javap -c} against
     * {@code org.springframework.boot.web.server.servlet.context.WebServerStartStopLifecycle#getPhase()}
     * in {@code spring-boot-web-server-4.1.0.jar}. {@code RollerLifecycle} must
     * report a phase strictly below this constant so that
     * {@code DefaultLifecycleProcessor} (which starts phases in ascending order
     * and stops them in descending order) starts Roller before the connector
     * opens and stops Roller after the connector closes. Note that
     * {@code SmartLifecycle}'s own default phase
     * ({@link org.springframework.context.SmartLifecycle#DEFAULT_PHASE}, plain
     * {@code Integer.MAX_VALUE}) is *above* this constant -- relying on the
     * default would invert both orderings, which is exactly the bug this test
     * guards against.
     */
    private static final int WEB_SERVER_START_STOP_LIFECYCLE_PHASE = Integer.MAX_VALUE - 2048;

    private RollerLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        // Guarantees WebloggerFactory.isBootstrapped() is false for the
        // duration of this test, regardless of what other tests sharing this
        // JVM left installed, so stop() below exercises the "nothing to shut
        // down" branch deterministically rather than touching whatever
        // Weblogger (mock or real) happens to be installed.
        MockWeblogger.installNotBootstrapped();
        lifecycle = new RollerLifecycle(mock(ApplicationContext.class), mock(ServletContext.class));
    }

    @AfterEach
    void tearDown() {
        MockWeblogger.uninstall();
    }

    @Test
    void phaseStartsBeforeAndStopsAfterTheWebServerLifecycle() {
        assertTrue(lifecycle.getPhase() < WEB_SERVER_START_STOP_LIFECYCLE_PHASE,
                "RollerLifecycle's phase must be below WebServerStartStopLifecycle's ("
                        + WEB_SERVER_START_STOP_LIFECYCLE_PHASE
                        + ") so it starts before the connector opens and stops after it closes");
    }

    @Test
    void notRunningUntilStarted() {
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void stopIsSafeWhenStartWasNeverCalled() {
        assertFalse(lifecycle.isRunning());
        assertDoesNotThrow(() -> lifecycle.stop());
        assertFalse(lifecycle.isRunning());
    }
}

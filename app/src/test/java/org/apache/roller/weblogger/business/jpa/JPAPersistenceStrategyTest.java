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
package org.apache.roller.weblogger.business.jpa;

import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.roller.testing.RollerDatabaseExtension;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link JPAPersistenceStrategy}.
 */
class JPAPersistenceStrategyTest {

    @Test
    void shutdownToleratesBeingCalledMoreThanOnce() throws Exception {
        // The Weblogger bean's destroyMethod="shutdown" and a caller invoking
        // Weblogger.shutdown() directly can both reach here for the same
        // instance; EclipseLink's EntityManagerFactory.close() throws
        // IllegalStateException on a second close, so shutdown() has to guard
        // against that itself.
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        JPAPersistenceStrategy strategy =
                new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());

        strategy.shutdown();
        assertDoesNotThrow(strategy::shutdown,
                "a second shutdown() on the same strategy must be a no-op, not throw");
    }

    @Test
    void releaseToleratesBeingCalledAfterShutdown() throws Exception {
        // JPAWebloggerImpl.shutdown() calls release() and then shutdown() on
        // this strategy on every pass, and (per shutdownToleratesBeingCalled-
        // MoreThanOnce above) that whole sequence can itself run twice for
        // the same instance. On the second pass, release() is reached against
        // an already-closed EntityManagerFactory; before this guard, that
        // threw IllegalStateException out of EntityManagerFactory.createEntityManager(),
        // caught and logged at ERROR rather than propagated, on every
        // graceful stop.
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        JPAPersistenceStrategy strategy =
                new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());

        strategy.shutdown();
        assertDoesNotThrow(strategy::release,
                "release() after shutdown() must be a no-op, not throw");
        assertDoesNotThrow(strategy::shutdown,
                "shutdown() after release()-after-shutdown() must still be a no-op, not throw");
    }

    @Test
    void handBuiltPersistenceUnitInfoBuildsAWorkingEmfAndAWeblogManagerQueryRoundTrips() throws Exception {
        // Task 2b: the constructor now builds the EntityManagerFactory via
        // PersistenceProvider#createContainerEntityManagerFactory with a
        // hand-built PersistenceUnitInfo instead of the classpath-scanning
        // Persistence.createEntityManagerFactory("RollerPU", props). This
        // proves that path actually produces a working EMF against a real
        // (Testcontainers) database: store a Weblog directly through the
        // strategy, then read it back through a JPA-backed WeblogManager
        // named query, exactly as the web tier would.
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        JPAPersistenceStrategy strategy =
                new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());
        try {
            Weblog weblog = new Weblog();
            weblog.setName("Persistence Strategy Test Weblog");
            weblog.setHandle("jpapersistencestrategytest");
            weblog.setEmailAddress("jpapersistencestrategytest@dev.null");
            weblog.setEditorTheme("journal");
            weblog.setLocale("en_US");
            weblog.setTimeZone("America/Los_Angeles");
            weblog.setDateCreated(new Date());
            weblog.setCreatorUserName("nobody");

            strategy.store(weblog);
            strategy.flush();

            WeblogManager weblogManager = new JPAWeblogManagerImpl(null, strategy);
            Weblog reloaded = weblogManager.getWeblogByHandle("jpapersistencestrategytest");

            assertNotNull(reloaded,
                    "a WeblogManager named query through the hand-built PersistenceUnitInfo "
                            + "path must find the weblog stored via the same EMF");
            assertEquals(weblog.getId(), reloaded.getId());

            strategy.remove(reloaded);
            strategy.flush();
        } finally {
            strategy.shutdown();
        }
    }

    /**
     * I5's underlying mechanism, tested directly against
     * {@code JPAPersistenceStrategy} rather than through {@code
     * VirtualHostRegistry}: a {@code runAfterCommit} callback must not run
     * until {@code flush()}'s {@code commit()} has actually succeeded, and
     * must run exactly once even though nothing re-queues it on a later
     * {@code flush()} call.
     */
    @Test
    void runAfterCommitActionsRunOnlyOnceAndOnlyAfterCommit() throws Exception {
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        JPAPersistenceStrategy strategy =
                new JPAPersistenceStrategy(WebloggerStartup.getDatabaseProvider());
        try {
            Weblog weblog = new Weblog();
            weblog.setName("Run After Commit Test Weblog");
            weblog.setHandle("runaftercommittest");
            weblog.setEmailAddress("runaftercommittest@dev.null");
            weblog.setEditorTheme("journal");
            weblog.setLocale("en_US");
            weblog.setTimeZone("America/Los_Angeles");
            weblog.setDateCreated(new Date());
            weblog.setCreatorUserName("nobody");

            strategy.store(weblog);

            AtomicInteger runs = new AtomicInteger();
            strategy.runAfterCommit(runs::incrementAndGet);

            assertEquals(0, runs.get(), "must not run before the commit actually happens");

            strategy.flush();
            assertEquals(1, runs.get(), "must run exactly once, once the commit has succeeded");

            WeblogManager weblogManager = new JPAWeblogManagerImpl(null, strategy);
            Weblog reloaded = weblogManager.getWeblogByHandle("runaftercommittest");
            strategy.remove(reloaded);
            strategy.flush();

            assertEquals(1, runs.get(),
                    "a later flush() with nothing newly queued must not re-run a stale action");
        } finally {
            strategy.shutdown();
        }
    }
}

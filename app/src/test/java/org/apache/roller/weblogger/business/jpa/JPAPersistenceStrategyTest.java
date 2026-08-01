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

import org.apache.roller.testing.RollerDatabaseExtension;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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
}

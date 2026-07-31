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
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@link MockWeblogger} test seam, because every controller test that
 * follows depends on it. If this harness quietly stopped installing its mocks, those
 * tests would fall back to a real (unbootstrapped) factory and fail in a way that
 * pointed at the controller rather than at here.
 */
class MockWebloggerTest {

    @AfterEach
    void tearDown() {
        MockWeblogger.uninstall();
    }

    @Test
    void installMakesTheStaticFactoryReturnTheMock() {
        MockWeblogger mocks = MockWeblogger.install();

        assertTrue(WebloggerFactory.isBootstrapped(),
                "install() must make WebloggerFactory report itself bootstrapped, since "
                        + "controllers and RollerSession both gate on isBootstrapped()");
        assertSame(mocks.weblogger(), WebloggerFactory.getWeblogger(),
                "WebloggerFactory must hand back the mocked Weblogger");
    }

    @Test
    void managersReachedThroughTheFactoryAreTheStubbableOnes() throws Exception {
        MockWeblogger mocks = MockWeblogger.install();

        Weblog expected = new Weblog();
        when(mocks.weblogManager().getWeblogByHandle("it_weblog")).thenReturn(expected);

        // The path a controller actually takes.
        Weblog actual = WebloggerFactory.getWeblogger()
                .getWeblogManager()
                .getWeblogByHandle("it_weblog");

        assertSame(expected, actual,
                "Stubbing the manager returned by MockWeblogger must affect the manager "
                        + "reached via WebloggerFactory, or controller tests cannot stub anything");
    }

    @Test
    void uninstallRestoresWhateverWasInstalledBefore() {
        // The database-backed tests bootstrap a real Weblogger into this same
        // static field and reuse it for the whole JVM, so uninstall() restores
        // the previous provider rather than clearing it. Nesting proves the
        // restore actually happens rather than the field merely being non-null.
        MockWeblogger outer = MockWeblogger.install();
        assertSame(outer.weblogger(), WebloggerFactory.getWeblogger());

        MockWeblogger inner = MockWeblogger.install();
        assertSame(inner.weblogger(), WebloggerFactory.getWeblogger(),
                "the second install must take over");

        MockWeblogger.uninstall();
        assertSame(outer.weblogger(), WebloggerFactory.getWeblogger(),
                "uninstall must hand the factory back to the provider that was "
                        + "installed before, not null and not the inner mock");

        MockWeblogger.uninstall();
    }

    @Test
    void uninstallingWithNothingPreviouslyInstalledLeavesTheFactoryUnbootstrapped() {
        // Guard the other direction: in a JVM where no real Weblogger was ever
        // bootstrapped, uninstall must leave the factory refusing service rather
        // than holding a stale mock.
        WebloggerProvider before = WebloggerFactory.currentProvider();
        WebloggerFactory.installProvider(null);

        MockWeblogger.install();
        MockWeblogger.uninstall();

        assertFalse(WebloggerFactory.isBootstrapped(),
                "with no prior provider, uninstall must leave the factory unbootstrapped");
        assertThrows(IllegalStateException.class, WebloggerFactory::getWeblogger,
                "an unbootstrapped factory must still refuse to hand out a Weblogger");

        WebloggerFactory.installProvider(before);
    }
}

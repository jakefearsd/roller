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
    void uninstallLeavesTheFactoryUnbootstrapped() {
        MockWeblogger.install();
        MockWeblogger.uninstall();

        assertFalse(WebloggerFactory.isBootstrapped(),
                "uninstall() must clear the provider; the factory is static, so a leaked "
                        + "mock would silently serve whatever test runs next in this JVM");
        assertThrows(IllegalStateException.class, WebloggerFactory::getWeblogger,
                "an unbootstrapped factory must still refuse to hand out a Weblogger");
    }
}

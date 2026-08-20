/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.controllers.core;

import java.util.List;

import org.apache.roller.weblogger.business.MockWeblogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for {@link LoginController}, the sign-in page.
 */
class LoginControllerTest {

    private LoginController controller;
    private ExtendedModelMap model;

    @BeforeEach
    void setUp() {
        MockWeblogger.install();
        controller = ControllerTestFixture.withMessages(new LoginController());
        model = new ExtendedModelMap();
    }

    @AfterEach
    void tearDown() {
        MockWeblogger.uninstall();
    }

    @Test
    void theSignInPageStartsWithNoComplaint() {
        String view = controller.execute(ControllerTestFixture.requestFor(null), model, null);

        assertEquals(".Login", view);
        assertEquals(List.of(), ControllerTestFixture.errors(model),
                "an untried login form must not accuse the visitor of anything");
        assertEquals("loginPage.title", model.getAttribute("pageTitle"));
    }

    @Test
    void comingBackWithAnErrorParameterShowsTheFailureMessage() {
        // Spring Security bounces a failed login back to /login.rol?error, and
        // this is the only thing that tells the user their attempt failed.
        String view = controller.execute(ControllerTestFixture.requestFor(null), model, "");

        assertEquals(".Login", view);
        assertEquals(List.of("error.password.mismatch"), ControllerTestFixture.errors(model));
    }

    @Test
    void theSignInPageIsReachableWithoutBeingSignedIn() {
        // Requiring a user here would make the login page unreachable.
        assertFalse(controller.isUserRequired());
        assertFalse(controller.isWeblogRequired());
        assertEquals("loginPage.title", controller.getPageTitle());
    }
}

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
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.List;

import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests for {@link UserAdminController}, the search box that leads to the user
 * editor.
 */
class UserAdminControllerTest {

    private MockWeblogger weblogger;
    private UserAdminController controller;
    private ExtendedModelMap model;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new UserAdminController());
        model = new ExtendedModelMap();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    @Test
    void searchPageOffersAnEmptyFormAndLooksNothingUp() {
        String view = controller.execute(ControllerTestFixture.requestFor(user()), model);

        assertEquals(".UserAdmin", view);
        assertEquals("userAdmin.title.searchUser", model.getAttribute("pageTitle"));
        assertEquals("userAdmin", model.getAttribute("actionName"));
        assertEquals("admin", model.getAttribute("desiredMenu"));
        assertNull(((CreateUserBean) model.getAttribute("bean")).getUserName());
        assertNotNull(model.getAttribute("menu"), "the admin menu is what makes the rest of the section reachable");
        // Nothing is searched until the admin submits a name.
        verifyNoInteractions(weblogger.weblogEntryManager());
    }

    @Test
    void submittingANameHandsOffToTheUserEditor() {
        CreateUserBean bean = new CreateUserBean();
        bean.setUserName("jake");

        String view = controller.edit(ControllerTestFixture.requestFor(user()), model, bean);

        assertEquals("redirect:/roller-ui/admin/modifyUser.rol?bean.userName=jake", view);
        assertEquals("admin", model.getAttribute("desiredMenu"),
                "the common model is populated in case the view is rendered rather than redirected");
    }

    @Test
    void theSearchFormItselfSuppliesTheModelAttribute() {
        // Spring calls this to seed @ModelAttribute("bean") on every request to
        // the controller; returning null would break form binding.
        assertNotNull(controller.getBean());
    }

    @Test
    void theUserSearchPageIsAdminOnlyAndNeedsNoWeblog() {
        // These two answers are what RollerHandlerInterceptor enforces before
        // the handler runs, so they are part of the controller's contract.
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
    }

    private static User user() {
        User user = new User();
        user.setUserName("admin");
        return user;
    }
}

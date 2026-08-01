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
package org.apache.roller.weblogger.ui.controllers.editor;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MemberResignController}.
 *
 * <p>Resigning revokes every permission the user has on the weblog, not some
 * subset, so the important assertion is that {@code ALL_ACTIONS} — not just
 * one action — is what gets revoked, and that the change is committed before
 * the user is told it succeeded.
 */
class MemberResignControllerTest extends EditorControllerTestSupport {

    private MemberResignController controller;
    private Model model;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    void setUp() {
        controller = prepare(new MemberResignController());
        model = newModel();
        redirectAttributes = newRedirectAttributes();
    }

    @Test
    void executeShowsTheConfirmationPage() {
        String view = controller.execute(request, model);

        assertEquals(".MemberResign", view);
    }

    @Test
    void resigningRevokesEveryPermissionAndRedirectsToTheMenu() throws Exception {
        registerMessage("yourWebsites.resigned", "resigned:{0}");
        when(request.getParameter("weblog")).thenReturn(WEBLOG_HANDLE);

        String view = controller.resign(request, model, redirectAttributes);

        assertEquals("redirect:/roller-ui/menu.rol", view);
        verify(weblogger.getUserManager())
                .revokeWeblogPermission(weblog, user, WeblogPermission.ALL_ACTIONS);
        assertTrue(weblogger.flushCount() > 0, "A resignation must be committed");
        assertEquals(java.util.List.of("resigned:" + WEBLOG_HANDLE), flashMessages(redirectAttributes));
    }

    @Test
    void resigningWithNoWeblogParameterStillSucceeds() throws Exception {
        registerMessage("yourWebsites.resigned", "resigned:{0}");
        when(request.getParameter("weblog")).thenReturn(null);

        controller.resign(request, model, redirectAttributes);

        assertEquals(java.util.List.of("resigned:"), flashMessages(redirectAttributes));
    }

    @Test
    void aFailedResignationStaysOnTheMenuWithAFlashError() throws Exception {
        doThrow(new WebloggerException("database down"))
                .when(weblogger.getUserManager()).revokeWeblogPermission(any(), any(), any());

        String view = controller.resign(request, model, redirectAttributes);

        assertEquals("redirect:/roller-ui/menu.rol", view);
        assertTrue(flashErrors(redirectAttributes).contains("Resignation failed - check system logs"),
                "Expected a resignation error, got: " + flashErrors(redirectAttributes));
        assertTrue(flashMessages(redirectAttributes).isEmpty(),
                "A failed resignation must not also report success");
    }
}

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

import java.util.List;

import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the declarative metadata every editor controller exposes.
 *
 * <p>None of this is decorative. {@code requiredWeblogPermissionActions} is the
 * authorization contract the {@code RollerHandlerInterceptor} enforces before
 * the handler is ever entered — a controller that silently drops back to the
 * inherited default changes who can reach it. {@code getDesiredMenu} and
 * {@code getActionName} decide which navigation is rendered and highlighted, so
 * a null there produces a page with no menu at all. These are one-line methods
 * that no behavioural test touches, which is exactly why they can rot unnoticed.
 */
class ControllerMetadataTest {

    @Test
    void entryEditingRequiresOnlyDraftPermissionSoContributorsCanWrite() {
        // EDIT_DRAFT rather than POST: a contributor must be able to write and
        // submit for review. The publish path checks POST separately.
        assertEquals(List.of(WeblogPermission.EDIT_DRAFT),
                new EntryEditController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.EDIT_DRAFT),
                new EntryRemoveController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.EDIT_DRAFT),
                new MemberResignController().requiredWeblogPermissionActions());
    }

    @Test
    void categoryAndMediaManagementRequirePostPermission() {
        // Categories and media are blog-wide structure, not a single draft, so
        // they sit above the contributor level.
        assertEquals(List.of(WeblogPermission.POST),
                new CategoryEditController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.POST),
                new CategoryRemoveController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.POST),
                new MediaFileAddController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.POST),
                new MediaFileViewController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.POST),
                new MediaFileEditController().requiredWeblogPermissionActions());
    }

    @Test
    void settingsMembershipAndTemplatesFallBackToWeblogAdmin() {
        // These do not override the inherited default, and must not: changing a
        // blog's configuration, its members or its templates is an owner's job.
        assertEquals(List.of(WeblogPermission.ADMIN),
                new WeblogConfigController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.ADMIN),
                new MembersController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.ADMIN),
                new MembersInviteController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.ADMIN),
                new TemplateEditController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.ADMIN),
                new StylesheetEditController().requiredWeblogPermissionActions());
    }

    @Test
    void everyEditorControllerRequiresALoggedInUserAndTheLoginPermission() {
        for (BaseController controller : allControllers()) {
            assertTrue(controller.isUserRequired(),
                    controller.getClass().getSimpleName() + " must require an authenticated user");
            assertEquals(List.of(GlobalPermission.LOGIN),
                    controller.requiredGlobalPermissionActions(),
                    controller.getClass().getSimpleName() + " must require the LOGIN permission");
        }
    }

    @Test
    void everyEditorControllerRendersUnderTheEditorMenu() {
        // A null menu id makes MenuHelper return no menu at all, leaving the
        // page with no navigation.
        for (BaseController controller : allControllers()) {
            assertEquals("editor", controller.getDesiredMenu(),
                    controller.getClass().getSimpleName() + " must render the editor menu");
        }
    }

    @Test
    void controllersThatHighlightAMenuItemDeclareTheirActionName() {
        // The action name is matched against the menu definition to decide
        // which tab is shown as current.
        assertEquals("members", new MembersController().getActionName());
        assertEquals("invite", new MembersInviteController().getActionName());
        assertEquals("memberResign", new MemberResignController().getActionName());
        assertEquals("categoryRemove", new CategoryRemoveController().getActionName());
        assertEquals("weblogConfig", new WeblogConfigController().getActionName());
        assertEquals("templateEdit", new TemplateEditController().getActionName());
        assertEquals("stylesheetEdit", new StylesheetEditController().getActionName());
        assertEquals("mediaFileAdd", new MediaFileAddController().getActionName());
        assertEquals("mediaFileView", new MediaFileViewController().getActionName());
        assertEquals("mediaFileEdit", new MediaFileEditController().getActionName());
    }

    @Test
    void controllersThatOwnAPageDeclareItsTitleKey() {
        assertEquals("memberPermissions.title", new MembersController().getPageTitle());
        assertEquals("inviteMember.title", new MembersInviteController().getPageTitle());
        assertEquals("yourWebsites.resign", new MemberResignController().getPageTitle());
        assertEquals("categoryDeleteOK.title", new CategoryRemoveController().getPageTitle());
        assertEquals("weblogEdit.deleteEntry", new EntryRemoveController().getPageTitle());
        assertEquals("websiteSettings.title", new WeblogConfigController().getPageTitle());
        assertEquals("pagesForm.title", new TemplateEditController().getPageTitle());
        assertEquals("stylesheetEdit.title", new StylesheetEditController().getPageTitle());
        assertEquals("mediaFileAdd.title", new MediaFileAddController().getPageTitle());
        assertEquals("mediaFileView.title", new MediaFileViewController().getPageTitle());
        assertEquals("mediaFile.edit.title", new MediaFileEditController().getPageTitle());
    }

    @Test
    void resigningFromAWeblogDoesNotItselfRequireOneToBeSelected() {
        // The resign form is reached from the "your weblogs" list rather than
        // from inside a weblog, so demanding an action weblog would make it
        // unreachable.
        assertFalse(new MemberResignController().isWeblogRequired());
    }

    private static List<BaseController> allControllers() {
        return List.of(
                new CategoryEditController(),
                new CategoryRemoveController(),
                new EntryEditController(),
                new EntryRemoveController(),
                new MediaFileAddController(),
                new MediaFileEditController(),
                new MediaFileViewController(),
                new MemberResignController(),
                new MembersController(),
                new MembersInviteController(),
                new StylesheetEditController(),
                new TemplateEditController(),
                new WeblogConfigController());
    }
}

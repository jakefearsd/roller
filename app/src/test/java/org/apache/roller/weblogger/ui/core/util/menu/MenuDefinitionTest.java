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
package org.apache.roller.weblogger.ui.core.util.menu;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the admin navigation is read correctly out of its XML definition.
 *
 * <p>{@code editor-menu.xml} and {@code admin-menu.xml} are the only statement
 * of which permissions guard which screens. They are parsed once at class load
 * and never validated, so a typo in an attribute name -- {@code weblogPerm} for
 * {@code weblogPerms}, say -- silently drops the guard rather than failing:
 * the attribute simply reads as null and the check is skipped. These tests pin
 * the parsed shape so that cannot happen quietly.
 */
class MenuDefinitionTest {

    @Test
    void theEditorMenuIsParsedAtClassLoad() {
        assertNotNull(MenuHelper.getParsedMenu("editor"),
                "The editor menu failed to parse at class load, which leaves every blogger "
                        + "with no navigation at all. Check editor-menu.xml is on the classpath "
                        + "at /org/apache/roller/weblogger/ui/menu/ and is well-formed.");
        assertNotNull(MenuHelper.getParsedMenu("admin"),
                "The admin menu failed to parse at class load. Check admin-menu.xml.");
        assertNull(MenuHelper.getParsedMenu("no-such-menu"),
                "An unknown menu id must not resolve to some other menu.");
    }

    @Test
    void theEditorMenuHasTheThreeBloggerTabsInOrder() {
        List<String> tabNames = names(MenuHelper.getParsedMenu("editor").getTabs());

        assertEquals(List.of("tabbedmenu.weblog", "tabbedmenu.design", "tabbedmenu.website"),
                tabNames,
                "The blogger's tabs, and their left-to-right order, come straight from "
                        + "editor-menu.xml. Reordering or renaming a tab here changes what every "
                        + "blogger sees.");
    }

    @Test
    void theWeblogTabListsTheContentManagementActions() {
        ParsedTab weblogTab = tab("editor", "tabbedmenu.weblog");

        assertEquals(List.of("entryAdd", "entries", "comments", "categories", "mediaFileView"),
                actions(weblogTab),
                "These are the actions the Weblog tab offers, in order. The first of them is "
                        + "also the tab's own link, so the order is user-visible.");
    }

    @Test
    void theDesignTabIsGuardedByTheCustomThemeProperty() {
        ParsedTab designTab = tab("editor", "tabbedmenu.design");

        assertEquals("themes.customtheme.allowed", designTab.getEnabledProperty(),
                "The Design tab must stay switched off on installations that do not allow "
                        + "custom themes. Losing this property exposes theme editing to "
                        + "everyone.");
        assertEquals(List.of("admin"), designTab.getWeblogPermissionActions(),
                "Only a weblog administrator may redesign a blog.");
        assertEquals(List.of("login"), designTab.getGlobalPermissionActions(),
                "The Design tab requires a logged-in user.");
        assertEquals(List.of("themeEdit", "stylesheetEdit", "templates"), actions(designTab),
                "The Design tab's items come from editor-menu.xml.");
    }

    @Test
    void theMembersItemIsGuardedByTheGroupBloggingProperty() {
        ParsedTabItem members = item("editor", "tabbedmenu.website", "members");

        assertEquals("groupblogging.enabled", members.getEnabledProperty(),
                "Inviting members must be hidden on single-user installations. Without this "
                        + "property the invite screen is reachable even when group blogging is "
                        + "switched off.");
        assertEquals(Set.of("invite"), members.getSubActions(),
                "The invite screen has to count as part of the Members item, or the tab stops "
                        + "looking selected once the user gets there.");
    }

    @Test
    void permissionsOnTheWeblogTabAndItsItemsAreParsed() {
        ParsedTab weblogTab = tab("editor", "tabbedmenu.weblog");

        assertEquals(List.of("edit_draft"), weblogTab.getWeblogPermissionActions(),
                "The Weblog tab is open to anyone who can at least draft a post.");
        assertEquals(List.of("login"), weblogTab.getGlobalPermissionActions(),
                "The Weblog tab requires a logged-in user.");
        assertEquals(List.of("edit_draft"),
                item("editor", "tabbedmenu.weblog", "entryAdd").getWeblogPermissionActions(),
                "Drafting a new entry needs only the draft permission.");
        assertEquals(List.of("post"),
                item("editor", "tabbedmenu.weblog", "comments").getWeblogPermissionActions(),
                "Moderating comments needs full posting rights, not just drafting. If this "
                        + "relaxes to edit_draft, draft-only contributors can moderate.");
    }

    @Test
    void subActionsKeepTheParentItemSelected() {
        assertEquals(Set.of("entryEdit", "entryRemove"),
                item("editor", "tabbedmenu.weblog", "entryAdd").getSubActions(),
                "Editing or removing an entry must keep the New Entry item highlighted.");
        assertEquals(Set.of("categoryAdd", "categoryEdit", "categoryRemove"),
                item("editor", "tabbedmenu.weblog", "categories").getSubActions(),
                "The category screens must keep the Categories item highlighted.");
        assertEquals(
                Set.of("templateAdd", "templateEdit", "templateRemove", "templatesRemove"),
                item("editor", "tabbedmenu.design", "templates").getSubActions(),
                "The template screens must keep the Templates item highlighted.");
        assertNull(item("editor", "tabbedmenu.design", "themeEdit").getSubActions(),
                "An item with no subactions attribute must leave the set null rather than "
                        + "inventing an empty one, which is how MenuHelper tells them apart.");
    }

    @Test
    void theAdminMenuIsGuardedByTheGlobalAdminPermission() {
        ParsedMenu adminMenu = MenuHelper.getParsedMenu("admin");

        assertEquals(List.of("tabbedmenu.admin"), names(adminMenu.getTabs()),
                "The server administration menu is a single tab.");
        assertEquals(List.of("admin"), adminMenu.getTabs().get(0).getGlobalPermissionActions(),
                "The whole admin menu hangs off the global admin permission. If this is lost, "
                        + "every logged-in user gets the server administration menu.");
        assertEquals(List.of("globalConfig", "userAdmin", "globalCommentManagement"),
                actions(adminMenu.getTabs().get(0)),
                "The admin tab's screens come from admin-menu.xml.");
        assertEquals(Set.of("createUser", "modifyUser"),
                item("admin", "tabbedmenu.admin", "userAdmin").getSubActions(),
                "Creating and editing users must keep the User Admin item highlighted.");
    }

    /**
     * Every item needs a name to label it and an action to link to. An item
     * missing either renders as an unlabelled or dead entry in the navigation.
     */
    @Test
    void everyItemInEveryMenuHasANameAndAnAction() {
        for (String menuId : List.of("editor", "admin")) {
            for (ParsedTab parsedTab : MenuHelper.getParsedMenu(menuId).getTabs()) {
                assertTrue(parsedTab.getName() != null && !parsedTab.getName().isEmpty(),
                        menuId + " has a tab with no name attribute; it will render with no "
                                + "label because the name is used as a message key.");
                assertTrue(!parsedTab.getTabItems().isEmpty(),
                        menuId + " tab " + parsedTab.getName() + " has no items, so it has no "
                                + "URL to link to either.");
                for (ParsedTabItem parsedItem : parsedTab.getTabItems()) {
                    assertNotNull(parsedItem.getName(),
                            menuId + "/" + parsedTab.getName() + " has an item with no name.");
                    assertNotNull(parsedItem.getAction(),
                            menuId + "/" + parsedTab.getName() + " item " + parsedItem.getName()
                                    + " has no action, so it links nowhere.");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // The parser itself
    // ------------------------------------------------------------------

    @Test
    void everyAttributeOnATabAndItemIsRead() throws Exception {
        ParsedMenu parsed = MenuFixture.parse("""
                <menu-bar>
                  <menu name="tab.one" weblogPerms="post,admin" globalPerms="login,weblog"
                        enabledProperty="feature.on" disabledProperty="feature.off">
                    <menu-item action="first" name="item.first" subactions="a,b"
                               weblogPerms="edit_draft" globalPerms="login"
                               enabledProperty="item.on" disabledProperty="item.off"/>
                  </menu>
                </menu-bar>
                """);

        ParsedTab parsedTab = parsed.getTabs().get(0);
        assertEquals("tab.one", parsedTab.getName(), "tab name attribute was not read");
        assertEquals(List.of("post", "admin"), parsedTab.getWeblogPermissionActions(),
                "A comma-separated weblogPerms list must become one action per entry.");
        assertEquals(List.of("login", "weblog"), parsedTab.getGlobalPermissionActions(),
                "A comma-separated globalPerms list must become one action per entry.");
        assertEquals("feature.on", parsedTab.getEnabledProperty(),
                "enabledProperty was not read");
        assertEquals("feature.off", parsedTab.getDisabledProperty(),
                "disabledProperty was not read");

        ParsedTabItem parsedItem = parsedTab.getTabItems().get(0);
        assertEquals("item.first", parsedItem.getName(), "item name attribute was not read");
        assertEquals("first", parsedItem.getAction(), "item action attribute was not read");
        assertEquals(Set.of("a", "b"), parsedItem.getSubActions(),
                "subactions must be split on commas");
        assertEquals(List.of("edit_draft"), parsedItem.getWeblogPermissionActions(),
                "item weblogPerms was not read");
        assertEquals(List.of("login"), parsedItem.getGlobalPermissionActions(),
                "item globalPerms was not read");
        assertEquals("item.on", parsedItem.getEnabledProperty(),
                "item enabledProperty was not read");
        assertEquals("item.off", parsedItem.getDisabledProperty(),
                "item disabledProperty was not read");
    }

    /**
     * Absent attributes must stay null, because MenuHelper uses null to mean
     * "no check to make". An empty list would read as a permission requirement
     * nobody can satisfy.
     */
    @Test
    void absentAttributesStayNull() throws Exception {
        ParsedMenu parsed = MenuFixture.parse("""
                <menu-bar>
                  <menu name="tab.one">
                    <menu-item action="first" name="item.first"/>
                  </menu>
                </menu-bar>
                """);

        ParsedTab parsedTab = parsed.getTabs().get(0);
        assertNull(parsedTab.getWeblogPermissionActions(),
                "A tab with no weblogPerms must not end up with an empty permission list; "
                        + "MenuHelper treats null as 'no check required'.");
        assertNull(parsedTab.getGlobalPermissionActions(), "globalPerms should be null");
        assertNull(parsedTab.getEnabledProperty(), "enabledProperty should be null");
        assertNull(parsedTab.getDisabledProperty(), "disabledProperty should be null");
        assertNull(parsedTab.getTabItems().get(0).getSubActions(), "subActions should be null");
    }

    @Test
    void aMenuWithNoTabsParsesToAnEmptyMenu() throws Exception {
        assertEquals(List.of(), MenuFixture.parse("<menu-bar/>").getTabs(),
                "An empty menu definition should produce an empty menu, not a null one that "
                        + "blows up when the navigation is rendered.");
    }

    @Test
    void aMissingMenuResourceIsReportedRatherThanIgnored() {
        IOException thrown = assertThrows(IOException.class,
                () -> MenuHelper.unmarshall(null),
                "A menu definition that cannot be found must fail loudly. Silently returning "
                        + "an empty menu would leave users staring at navigation with no tabs "
                        + "and no explanation.");
        assertTrue(thrown.getMessage().contains("null"),
                "The failure should say the stream was missing; got: " + thrown.getMessage());
    }

    @Test
    void malformedXmlIsReportedRatherThanIgnored() {
        assertThrows(Exception.class,
                () -> MenuHelper.unmarshall(
                        new ByteArrayInputStream("<menu-bar>".getBytes(StandardCharsets.UTF_8))),
                "Truncated XML must not parse into a half-built menu.");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ParsedTab tab(String menuId, String tabName) {
        for (ParsedTab candidate : MenuHelper.getParsedMenu(menuId).getTabs()) {
            if (tabName.equals(candidate.getName())) {
                return candidate;
            }
        }
        throw new AssertionError("No tab named " + tabName + " in the " + menuId
                + " menu. Available: " + names(MenuHelper.getParsedMenu(menuId).getTabs()));
    }

    private static ParsedTabItem item(String menuId, String tabName, String action) {
        for (ParsedTabItem candidate : tab(menuId, tabName).getTabItems()) {
            if (action.equals(candidate.getAction())) {
                return candidate;
            }
        }
        throw new AssertionError("No item with action " + action + " in " + menuId + "/"
                + tabName + ". Available: " + actions(tab(menuId, tabName)));
    }

    private static List<String> names(List<ParsedTab> tabs) {
        List<String> result = new ArrayList<>();
        for (ParsedTab parsedTab : tabs) {
            result.add(parsedTab.getName());
        }
        return result;
    }

    private static List<String> actions(ParsedTab parsedTab) {
        List<String> result = new ArrayList<>();
        for (ParsedTabItem parsedItem : parsedTab.getTabItems()) {
            result.add(parsedItem.getAction());
        }
        return result;
    }
}

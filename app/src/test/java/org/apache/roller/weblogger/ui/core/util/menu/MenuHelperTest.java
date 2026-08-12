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

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RollerPermission;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests how the admin navigation is filtered for the signed-in user.
 *
 * <p>This is a security surface with a quiet failure mode in both directions.
 * Filter too little and a draft-only contributor is shown -- and can reach --
 * the comment moderation or theme editing screens. Filter too much and an
 * administrator loses the only route to a screen, with nothing in the logs to
 * say why.
 *
 * <p>The permission checks are answered by a stub that models a user holding a
 * fixed set of actions, so each case can state exactly which permissions the
 * user has and read off exactly which menu entries that buys them.
 */
class MenuHelperTest {

    private static final String WEBLOG_HANDLE = "myblog";

    private User user;
    private Weblog weblog;
    private UserManager userManager;

    /** Global actions the fixture user holds, e.g. "login", "admin". */
    private final Set<String> globalActions = new HashSet<>();

    /** Weblog actions the fixture user holds on {@link #weblog}. */
    private final Set<String> weblogActions = new HashSet<>();

    /** Config properties that are switched on for this scenario. */
    private final Set<String> enabledProperties = new HashSet<>();

    @BeforeEach
    void setUp() throws WebloggerException {
        user = new User();
        user.setUserName("jane");

        weblog = new Weblog();
        weblog.setHandle(WEBLOG_HANDLE);
        weblog.setEditorTheme("journal");

        userManager = mock(UserManager.class);
        // A permission is granted when the user holds every action it asks for.
        when(userManager.checkPermission(any(), eq(user))).thenAnswer(invocation -> {
            RollerPermission requested = invocation.getArgument(0);
            Set<String> held = requested instanceof GlobalPermission ? globalActions : weblogActions;
            return held.containsAll(requested.getActionsAsList());
        });
    }

    // ------------------------------------------------------------------
    // The shipped admin menu
    // ------------------------------------------------------------------

    @Test
    void aServerAdministratorSeesTheWholeAdminMenu() throws Exception {
        globalActions.add("admin");

        Menu menu = build("admin", MenuHelper.getParsedMenu("admin"), "globalConfig");

        assertEquals(List.of("tabbedmenu.admin"), tabKeys(menu),
                "A server administrator must see the administration tab.");
        assertEquals(List.of("globalConfig", "userAdmin", "maintenance"),
                itemActions(menu.getTabs().get(0)),
                "A server administrator must see every administration screen, including "
                        + "Maintenance now that it has moved off the blog-author tabs.");
    }

    @Test
    void aUserWithoutTheGlobalAdminPermissionSeesNoAdminMenuAtAll() throws Exception {
        globalActions.add("login");
        globalActions.add("weblog");

        Menu menu = build("admin", MenuHelper.getParsedMenu("admin"), "globalConfig");

        assertEquals(List.of(), tabKeys(menu),
                "A user who can log in and blog but is not a server administrator must be "
                        + "shown no part of the administration menu. Anything visible here is "
                        + "a route to global configuration and user management.");
    }

    @Test
    void theAdminMenuIsGuardedByExactlyTheGlobalAdminPermission() throws Exception {
        globalActions.add("admin");

        build("admin", MenuHelper.getParsedMenu("admin"), null);

        ArgumentCaptor<RollerPermission> checked = ArgumentCaptor.forClass(RollerPermission.class);
        verify(userManager, atLeastOnce()).checkPermission(checked.capture(), eq(user));
        assertEquals(List.of("admin"), checked.getValue().getActionsAsList(),
                "The admin menu must be gated on the global \"admin\" action. A different "
                        + "action here either locks administrators out or lets others in.");
        assertTrue(checked.getValue() instanceof GlobalPermission,
                "Server administration is a global permission, not a per-weblog one.");
    }

    // ------------------------------------------------------------------
    // The shipped editor menu
    // ------------------------------------------------------------------

    @Test
    void aWeblogAdministratorSeesEveryEditorTab() throws Exception {
        beAWeblogAdministrator();

        Menu menu = build("editor", MenuHelper.getParsedMenu("editor"), null);

        assertEquals(List.of("tabbedmenu.weblog", "tabbedmenu.design", "tabbedmenu.website"),
                tabKeys(menu),
                "A weblog administrator on an installation with custom themes enabled should "
                        + "see all three tabs.");
        assertEquals(List.of("entryAdd", "entries", "submissions", "categories", "pages",
                        "mediaFileView"),
                itemActions(menu.getTabs().get(0)),
                "A weblog administrator should see every content screen.");
        assertEquals(List.of("weblogConfig", "members"),
                itemActions(menu.getTabs().get(2)),
                "A weblog administrator should see every settings screen. Maintenance is no "
                        + "longer one of them -- it moved to Global Admin.");
    }

    /**
     * The narrowest real role: someone who may write drafts but not publish.
     * Everything that needs the "post" permission has to disappear, including
     * comment moderation and the media library.
     */
    @Test
    void aDraftOnlyContributorSeesOnlyTheScreensTheyCanUse() throws Exception {
        globalActions.add("login");
        weblogActions.add("edit_draft");
        enabledProperties.add("themes.customtheme.allowed");
        enabledProperties.add("groupblogging.enabled");

        Menu menu = build("editor", MenuHelper.getParsedMenu("editor"), null);

        assertEquals(List.of("tabbedmenu.weblog"), tabKeys(menu),
                "Design and settings both require weblog admin rights, so a draft-only "
                        + "contributor must not see either tab.");
        assertEquals(List.of("entryAdd"), itemActions(menu.getTabs().get(0)),
                "Only drafting a new entry needs no more than edit_draft. Comment moderation, "
                        + "the archive list, categories and media files all require post "
                        + "rights, and showing them hands a contributor links they cannot use "
                        + "-- or worse, can.");
        assertEquals("entryAdd", menu.getTabs().get(0).getAction(),
                "The tab's own link must be its first visible item.");
    }

    /**
     * Permission checks must name the weblog being edited. A check against the
     * wrong weblog would grant a user rights they hold on some other blog.
     */
    @Test
    void weblogPermissionsAreCheckedAgainstTheWeblogBeingEdited() throws Exception {
        beAWeblogAdministrator();

        build("editor", MenuHelper.getParsedMenu("editor"), null);

        ArgumentCaptor<RollerPermission> checked = ArgumentCaptor.forClass(RollerPermission.class);
        verify(userManager, atLeastOnce()).checkPermission(checked.capture(), eq(user));
        List<WeblogPermission> weblogChecks = new ArrayList<>();
        for (RollerPermission permission : checked.getAllValues()) {
            if (permission instanceof WeblogPermission weblogPermission) {
                weblogChecks.add(weblogPermission);
            }
        }
        assertFalse(weblogChecks.isEmpty(),
                "No weblog permission was checked at all while building the editor menu, so "
                        + "the per-weblog guards in editor-menu.xml are not being applied.");
        for (WeblogPermission permission : weblogChecks) {
            assertEquals(WEBLOG_HANDLE, permission.getObjectId(),
                    "A weblog permission was checked against \"" + permission.getObjectId()
                            + "\" rather than the weblog being edited (" + WEBLOG_HANDLE + ").");
        }
    }

    @Test
    void theDesignTabDisappearsWhereCustomThemesAreNotAllowed() throws Exception {
        beAWeblogAdministrator();
        enabledProperties.remove("themes.customtheme.allowed");

        Menu menu = build("editor", MenuHelper.getParsedMenu("editor"), null);

        assertEquals(List.of("tabbedmenu.weblog", "tabbedmenu.website"), tabKeys(menu),
                "With themes.customtheme.allowed switched off the Design tab must go, and "
                        + "only that tab.");
    }

    @Test
    void theMembersScreenDisappearsWhereGroupBloggingIsSwitchedOff() throws Exception {
        beAWeblogAdministrator();
        enabledProperties.remove("groupblogging.enabled");

        Menu menu = build("editor", MenuHelper.getParsedMenu("editor"), null);

        assertEquals(List.of("weblogConfig"),
                itemActions(tabNamed(menu, "tabbedmenu.website")),
                "With group blogging switched off the Members screen must go, and the rest of "
                        + "the settings tab must stay.");
    }

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

    @Test
    void theItemForTheCurrentScreenIsHighlightedAlongWithItsTab() throws Exception {
        beAWeblogAdministrator();

        Menu menu = build("editor", MenuHelper.getParsedMenu("editor"), "categories");

        assertEquals(List.of("categories"), selectedItemActions(menu),
                "The Categories screen must highlight the Categories item, and only it.");
        assertEquals(List.of("tabbedmenu.weblog"), selectedTabKeys(menu),
                "The tab containing the current screen must be highlighted, and only it.");
    }

    /**
     * The category editor is reached from the Categories screen but is a
     * separate action. Without the subaction mapping the navigation goes blank
     * the moment a user edits anything.
     */
    @Test
    void aSubScreenKeepsItsParentItemHighlighted() throws Exception {
        beAWeblogAdministrator();

        Menu menu = build("editor", MenuHelper.getParsedMenu("editor"), "categoryEdit");

        assertEquals(List.of("categories"), selectedItemActions(menu),
                "categoryEdit is listed as a subaction of the Categories item, so editing a "
                        + "category must keep Categories highlighted.");
    }

    @Test
    void nothingIsHighlightedWhenThereIsNoCurrentScreen() throws Exception {
        beAWeblogAdministrator();

        Menu menu = build("editor", MenuHelper.getParsedMenu("editor"), null);

        assertEquals(List.of(), selectedItemActions(menu),
                "With no current action nothing should be highlighted.");
        assertEquals(List.of(), selectedTabKeys(menu),
                "With no current action no tab should be highlighted.");
    }

    @Test
    void anActionThatIsNotInTheMenuHighlightsNothing() throws Exception {
        beAWeblogAdministrator();

        Menu menu = build("editor", MenuHelper.getParsedMenu("editor"), "somethingElse");

        assertEquals(List.of(), selectedItemActions(menu),
                "An action with no menu entry -- a popup, say -- must not highlight an "
                        + "unrelated item.");
    }

    /** Only one item per tab is highlighted, even if two entries share an action. */
    @Test
    void onlyTheFirstMatchingItemInATabIsHighlighted() throws Exception {
        ParsedMenu config = MenuFixture.parse("""
                <menu-bar>
                  <menu name="tab.one">
                    <menu-item action="shared" name="item.first"/>
                    <menu-item action="shared" name="item.second"/>
                  </menu>
                </menu-bar>
                """);

        Menu menu = build(MenuFixture.MENU_ID, config, "shared");

        assertEquals(List.of("item.first"), selectedItemKeys(menu),
                "Two items pointing at the same action must not both light up; the first one "
                        + "wins.");
    }

    // ------------------------------------------------------------------
    // Enabled and disabled properties
    // ------------------------------------------------------------------

    @Test
    void aDisabledPropertyHidesATabWhileItIsTrue() throws Exception {
        ParsedMenu config = MenuFixture.parse("""
                <menu-bar>
                  <menu name="tab.hidden" disabledProperty="feature.off">
                    <menu-item action="one" name="item.one"/>
                  </menu>
                  <menu name="tab.shown">
                    <menu-item action="two" name="item.two"/>
                  </menu>
                </menu-bar>
                """);

        enabledProperties.add("feature.off");
        assertEquals(List.of("tab.shown"), tabKeys(build(MenuFixture.MENU_ID, config, null)),
                "disabledProperty is the inverse of enabledProperty: while the property is "
                        + "true the tab must be hidden.");

        enabledProperties.remove("feature.off");
        assertEquals(List.of("tab.hidden", "tab.shown"),
                tabKeys(build(MenuFixture.MENU_ID, config, null)),
                "With the disabling property false the tab must come back.");
    }

    @Test
    void aDisabledPropertyHidesAnItemWhileItIsTrue() throws Exception {
        ParsedMenu config = MenuFixture.parse("""
                <menu-bar>
                  <menu name="tab.one">
                    <menu-item action="one" name="item.one" disabledProperty="feature.off"/>
                    <menu-item action="two" name="item.two"/>
                  </menu>
                </menu-bar>
                """);

        enabledProperties.add("feature.off");
        Menu hidden = build(MenuFixture.MENU_ID, config, null);
        assertEquals(List.of("two"), itemActions(hidden.getTabs().get(0)),
                "An item whose disabling property is true must not be shown.");
        assertEquals("two", hidden.getTabs().get(0).getAction(),
                "With the first item hidden the tab must link to the first item that is left, "
                        + "not to the hidden one.");

        enabledProperties.remove("feature.off");
        assertEquals(List.of("one", "two"),
                itemActions(build(MenuFixture.MENU_ID, config, null).getTabs().get(0)),
                "With the disabling property false the item must come back.");
    }

    @Test
    void anEnabledPropertyThatIsOffHidesTheItem() throws Exception {
        ParsedMenu config = MenuFixture.parse("""
                <menu-bar>
                  <menu name="tab.one">
                    <menu-item action="one" name="item.one" enabledProperty="feature.on"/>
                    <menu-item action="two" name="item.two"/>
                  </menu>
                </menu-bar>
                """);

        assertEquals(List.of("two"), itemActions(build(MenuFixture.MENU_ID, config, null)
                        .getTabs().get(0)),
                "An item is only shown while its enabling property is true.");

        enabledProperties.add("feature.on");
        assertEquals(List.of("one", "two"),
                itemActions(build(MenuFixture.MENU_ID, config, null).getTabs().get(0)),
                "Switching the enabling property on must reveal the item.");
    }

    /**
     * A tab carrying both attributes uses the enabling one; the disabling one
     * is only consulted when there is no enabling property to consult.
     */
    @Test
    void theEnablingPropertyWinsWhenATabCarriesBoth() throws Exception {
        ParsedMenu config = MenuFixture.parse("""
                <menu-bar>
                  <menu name="tab.one" enabledProperty="feature.on" disabledProperty="feature.off">
                    <menu-item action="one" name="item.one"/>
                  </menu>
                </menu-bar>
                """);

        enabledProperties.add("feature.on");
        enabledProperties.add("feature.off");

        assertEquals(List.of("tab.one"), tabKeys(build(MenuFixture.MENU_ID, config, null)),
                "Both properties are true. The enabling property is checked first and shows "
                        + "the tab, so the disabling property must not be consulted at all.");
    }

    // ------------------------------------------------------------------
    // Permission checks that are not made
    // ------------------------------------------------------------------

    /**
     * Permission checks cost a database round trip per tab, which is why the
     * shipped menus lean on them sparingly. An entry with no permission
     * attributes must not trigger one.
     */
    @Test
    void anUnguardedMenuCostsNoPermissionChecks() throws Exception {
        ParsedMenu config = MenuFixture.parse("""
                <menu-bar>
                  <menu name="tab.one">
                    <menu-item action="one" name="item.one"/>
                  </menu>
                </menu-bar>
                """);

        Menu menu = build(MenuFixture.MENU_ID, config, null);

        assertEquals(List.of("tab.one"), tabKeys(menu),
                "A tab with no permission attributes is open to everyone.");
        verifyNoInteractions(userManager);
    }

    @Test
    void anEmptyPermissionListIsNotAGuard() throws Exception {
        ParsedMenu config = MenuFixture.parse("""
                <menu-bar>
                  <menu name="tab.one" weblogPerms="" globalPerms="">
                    <menu-item action="one" name="item.one" weblogPerms="" globalPerms=""/>
                  </menu>
                </menu-bar>
                """);

        Menu menu = build(MenuFixture.MENU_ID, config, null);

        assertEquals(List.of("one"), itemActions(menu.getTabs().get(0)),
                "An empty permission list means no actions are required, so the entry stays "
                        + "visible. Treating it as a requirement would hide the entry from "
                        + "everyone, including administrators.");
        verifyNoInteractions(userManager);
    }

    /**
     * A menu item can carry its own global permission, independently of the tab
     * it sits in. None of the shipped menus use this today, so nothing else
     * would notice the check being dropped -- but the attribute is documented
     * in editor-menu.xml and the next person to reach for it has to be able to
     * trust it.
     */
    @Test
    void anItemCanCarryItsOwnGlobalPermission() throws Exception {
        ParsedMenu config = MenuFixture.parse("""
                <menu-bar>
                  <menu name="tab.one">
                    <menu-item action="forAdmins" name="item.admin" globalPerms="admin"/>
                    <menu-item action="forEveryone" name="item.everyone"/>
                  </menu>
                </menu-bar>
                """);

        globalActions.add("login");
        assertEquals(List.of("forEveryone"),
                itemActions(build(MenuFixture.MENU_ID, config, null).getTabs().get(0)),
                "A user without the global admin permission must not be shown an item that "
                        + "requires it, even when the tab around it is open to everyone.");

        globalActions.add("admin");
        assertEquals(List.of("forAdmins", "forEveryone"),
                itemActions(build(MenuFixture.MENU_ID, config, null).getTabs().get(0)),
                "An administrator must see the item their permission unlocks.");
    }

    /**
     * A permission lookup that blows up must hide the tab rather than let it
     * through -- failing open would show screens to users whose rights could
     * not be established.
     */
    @Test
    void aFailedPermissionLookupHidesTheTab() throws Exception {
        UserManager failing = mock(UserManager.class);
        when(failing.checkPermission(any(), any()))
                .thenThrow(new WebloggerException("user store unavailable"));

        Menu menu = MenuHelper.buildMenu("admin", MenuHelper.getParsedMenu("admin"), null,
                user, weblog, failing, enabledProperties::contains);

        assertEquals(List.of(), tabKeys(menu),
                "When the permission lookup fails the tab must be hidden. Showing it would "
                        + "expose administration screens to a user whose rights could not be "
                        + "checked.");
    }

    // ------------------------------------------------------------------
    // The custom-theme convenience override
    // ------------------------------------------------------------------

    @Test
    void theDesignTabOpensOnTemplatesForABlogWithACustomTheme() throws Exception {
        beAWeblogAdministrator();
        weblog.setEditorTheme(WeblogTheme.CUSTOM);

        Menu menu = build("editor", MenuHelper.getParsedMenu("editor"), null);

        assertEquals("templates", tabNamed(menu, "tabbedmenu.design").getAction(),
                "A blog on a custom theme has nothing to choose on the theme screen, so the "
                        + "Design tab is meant to open straight on Templates.");
        assertEquals(List.of("themeEdit", "stylesheetEdit", "templates"),
                itemActions(tabNamed(menu, "tabbedmenu.design")),
                "The override changes where the tab points, not which items it lists.");
    }

    @Test
    void theDesignTabOpensOnThemeSelectionForABlogWithASharedTheme() throws Exception {
        beAWeblogAdministrator();
        weblog.setEditorTheme("journal");

        Menu menu = build("editor", MenuHelper.getParsedMenu("editor"), null);

        assertEquals("themeEdit", tabNamed(menu, "tabbedmenu.design").getAction(),
                "A blog on a shared theme should open the Design tab on theme selection -- "
                        + "the first item, as usual.");
    }

    /** The override is a convenience for the blogger's menu only. */
    @Test
    void theCustomThemeOverrideDoesNotLeakIntoOtherMenus() throws Exception {
        beAWeblogAdministrator();
        weblog.setEditorTheme(WeblogTheme.CUSTOM);

        ParsedMenu config = MenuFixture.parse("""
                <menu-bar>
                  <menu name="tabbedmenu.design">
                    <menu-item action="themeEdit" name="item.theme"/>
                    <menu-item action="templates" name="item.templates"/>
                  </menu>
                </menu-bar>
                """);

        Menu menu = build(MenuFixture.MENU_ID, config, null);

        assertEquals("themeEdit", menu.getTabs().get(0).getAction(),
                "The custom-theme override is keyed on the \"editor\" menu id. A tab that "
                        + "merely shares the name in another menu must keep pointing at its "
                        + "first item.");
    }

    // ------------------------------------------------------------------
    // The public entry point
    // ------------------------------------------------------------------

    /**
     * The whole thing through its real entry point, with only the business
     * tier stubbed: the user manager and the properties manager are the two
     * collaborators {@code getMenu} looks up for itself, and wiring either of
     * them to the wrong place breaks every admin page at once.
     */
    @Test
    void getMenuBuildsTheEditorMenuThroughTheRealCollaborators() throws Exception {
        beAWeblogAdministrator();
        PropertiesManager properties = mock(PropertiesManager.class);
        // Answered from the runtime (database-backed) configuration...
        when(properties.getProperty("themes.customtheme.allowed"))
                .thenReturn(new RuntimeConfigProperty("themes.customtheme.allowed", "true"));
        // ...and this one is not, so the static roller.properties value applies.
        when(properties.getProperty("groupblogging.enabled")).thenReturn(null);

        try (MockedStatic<WebloggerFactory> factory = businessTier(properties)) {
            Menu menu = MenuHelper.getMenu("editor", "categories", user, weblog);

            assertEquals(List.of("tabbedmenu.weblog", "tabbedmenu.design", "tabbedmenu.website"),
                    tabKeys(menu),
                    "A weblog administrator should get all three tabs from the real entry "
                            + "point, just as from buildMenu.");
            assertEquals(List.of("categories"), selectedItemActions(menu),
                    "The current action must still be highlighted when the menu is built "
                            + "through getMenu.");
            assertTrue(itemActions(tabNamed(menu, "tabbedmenu.website")).contains("members"),
                    "groupblogging.enabled is true in roller.properties, so with no runtime "
                            + "override the Members screen must be shown. Missing here means "
                            + "the fall-through from runtime to static configuration is broken "
                            + "and every property-guarded menu entry has vanished.");
        }
    }

    /**
     * A property that neither configuration knows about reads as false, so the
     * entry it guards stays hidden. Defaulting the other way would switch on
     * every property-guarded screen on any installation that has not set the
     * property -- which is most of them.
     */
    @Test
    void anUnknownPropertyLeavesItsTabHidden() throws Exception {
        beAWeblogAdministrator();
        PropertiesManager properties = mock(PropertiesManager.class);
        // themes.customtheme.allowed is in neither the runtime configuration
        // nor roller.properties.
        when(properties.getProperty("themes.customtheme.allowed")).thenReturn(null);

        try (MockedStatic<WebloggerFactory> factory = businessTier(properties)) {
            Menu menu = MenuHelper.getMenu("editor", null, user, weblog);

            assertFalse(tabKeys(menu).contains("tabbedmenu.design"),
                    "themes.customtheme.allowed is set in neither configuration, so it must "
                            + "read as false and the Design tab must stay hidden. Tabs "
                            + "present: " + tabKeys(menu));
        }
    }

    /** Runtime configuration overrides the static default, not the other way round. */
    @Test
    void aRuntimePropertyOverridesTheStaticDefault() throws Exception {
        beAWeblogAdministrator();
        PropertiesManager properties = mock(PropertiesManager.class);
        when(properties.getProperty("themes.customtheme.allowed"))
                .thenReturn(new RuntimeConfigProperty("themes.customtheme.allowed", "false"));

        try (MockedStatic<WebloggerFactory> factory = businessTier(properties)) {
            Menu menu = MenuHelper.getMenu("editor", null, user, weblog);

            assertFalse(tabKeys(menu).contains("tabbedmenu.design"),
                    "An administrator switched custom themes off at runtime; the Design tab "
                            + "must go even though it is not disabled in roller.properties. "
                            + "Tabs present: " + tabKeys(menu));
        }
    }

    @Test
    void anUnknownMenuIdProducesNoMenu() {
        assertNull(MenuHelper.getMenu("no-such-menu", "globalConfig", user, weblog),
                "An unknown menu id must produce no menu rather than an empty one, which the "
                        + "JSP would render as a bare, tabless menu bar.");
    }

    @Test
    void aNullMenuIdProducesNoMenu() {
        assertNull(MenuHelper.getMenu(null, "globalConfig", user, weblog),
                "A null menu id must be handled rather than thrown on: it reaches here from a "
                        + "controller that did not declare a menu.");
    }

    // ------------------------------------------------------------------
    // Menu containers
    // ------------------------------------------------------------------

    @Test
    void menuContainersHoldWhatIsPutInThem() {
        MenuTabItem item = new MenuTabItem();
        item.setKey("item.key");
        item.setAction("someAction");

        MenuTab menuTab = new MenuTab();
        menuTab.setKey("tab.key");
        menuTab.setAction("tabAction");
        menuTab.addItem(item);

        Menu menu = new Menu();
        menu.setTabs(new ArrayList<>(List.of(menuTab)));

        assertEquals("item.key", item.getKey(), "item key did not round-trip");
        assertEquals("someAction", item.getAction(), "item action did not round-trip");
        assertFalse(item.isSelected(), "A newly built item must not start out highlighted");
        assertFalse(menuTab.isSelected(), "A newly built tab must not start out highlighted");
        assertEquals(List.of(item), menuTab.getItems(), "tab items did not round-trip");
        assertEquals(List.of(menuTab), menu.getTabs(), "menu tabs did not round-trip");

        menuTab.setItems(new ArrayList<>());
        assertEquals(List.of(), menuTab.getItems(),
                "setItems must replace the item list, not append to it");
    }

    @Test
    void parsedContainersHoldWhatIsPutInThem() {
        ParsedTabItem parsedItem = new ParsedTabItem();
        parsedItem.setName("item.key");

        ParsedTab parsedTab = new ParsedTab();
        parsedTab.setName("tab.key");
        parsedTab.setTabItems(new ArrayList<>(List.of(parsedItem)));

        ParsedMenu parsedMenu = new ParsedMenu();
        parsedMenu.setTabs(new ArrayList<>(List.of(parsedTab)));

        assertEquals(List.of(parsedItem), parsedTab.getTabItems(),
                "setTabItems must replace the item list");
        assertEquals(List.of(parsedTab), parsedMenu.getTabs(),
                "setTabs must replace the tab list");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The common case: full rights on the weblog, everything switched on. */
    private void beAWeblogAdministrator() {
        globalActions.add("login");
        globalActions.add("weblog");
        weblogActions.add("edit_draft");
        weblogActions.add("post");
        weblogActions.add("admin");
        enabledProperties.add("themes.customtheme.allowed");
        enabledProperties.add("groupblogging.enabled");
    }

    /** Stands a mock Weblogger up behind the static factory {@code getMenu} uses. */
    private MockedStatic<WebloggerFactory> businessTier(PropertiesManager properties) {
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getUserManager()).thenReturn(userManager);
        when(weblogger.getPropertiesManager()).thenReturn(properties);

        MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class);
        factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
        return factory;
    }

    private Menu build(String menuId, ParsedMenu config, String currentAction)
            throws WebloggerException {
        Predicate<String> propertyEnabled = enabledProperties::contains;
        return MenuHelper.buildMenu(menuId, config, currentAction, user, weblog, userManager,
                propertyEnabled);
    }

    private static MenuTab tabNamed(Menu menu, String key) {
        for (MenuTab candidate : menu.getTabs()) {
            if (key.equals(candidate.getKey())) {
                return candidate;
            }
        }
        throw new AssertionError("No tab keyed " + key + " in the built menu. Present: "
                + tabKeys(menu));
    }

    private static List<String> tabKeys(Menu menu) {
        List<String> keys = new ArrayList<>();
        for (MenuTab menuTab : menu.getTabs()) {
            keys.add(menuTab.getKey());
        }
        return keys;
    }

    private static List<String> itemActions(MenuTab menuTab) {
        List<String> actions = new ArrayList<>();
        for (MenuTabItem item : menuTab.getItems()) {
            actions.add(item.getAction());
        }
        return actions;
    }

    private static List<String> selectedTabKeys(Menu menu) {
        List<String> keys = new ArrayList<>();
        for (MenuTab menuTab : menu.getTabs()) {
            if (menuTab.isSelected()) {
                keys.add(menuTab.getKey());
            }
        }
        return keys;
    }

    private static List<String> selectedItemActions(Menu menu) {
        List<String> actions = new ArrayList<>();
        for (MenuTab menuTab : menu.getTabs()) {
            for (MenuTabItem item : menuTab.getItems()) {
                if (item.isSelected()) {
                    actions.add(item.getAction());
                }
            }
        }
        return actions;
    }

    private static List<String> selectedItemKeys(Menu menu) {
        List<String> keys = new ArrayList<>();
        for (MenuTab menuTab : menu.getTabs()) {
            for (MenuTabItem item : menuTab.getItems()) {
                if (item.isSelected()) {
                    keys.add(item.getKey());
                }
            }
        }
        return keys;
    }
}

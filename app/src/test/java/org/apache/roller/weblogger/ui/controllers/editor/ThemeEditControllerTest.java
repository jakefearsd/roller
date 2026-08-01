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

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.themes.SharedTheme;
import org.apache.roller.weblogger.pojos.ThemeTemplate;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ThemeEditController}.
 *
 * <p>{@code save} folds two very different flows into one handler: adopting a
 * custom theme (optionally importing a shared theme's templates as the
 * starting point) and switching to a different shared theme outright. Both
 * paths have to clean up a custom stylesheet override left behind by the
 * theme being replaced, and the "did the theme actually change" check governs
 * whether a success message appears at all — getting it backwards would
 * either spam a no-op save or silently swallow a real one.
 */
class ThemeEditControllerTest extends EditorControllerTestSupport {

    private ThemeEditController controller;
    private Model model;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new ThemeEditController());
        model = newModel();
        when(weblogger.getThemeManager().getEnabledThemesList()).thenReturn(List.of());

        // Weblog.getTheme() routes through WebloggerFactory.getWeblogger().getThemeManager()
        // (the same static factory MockWeblogger installs into), not the controller's
        // injected weblogger field. save() calls it unconditionally via
        // isSharedThemeCustomStylesheet() before even branching on themeType, so
        // every test needs a non-null default here or it NPEs before the
        // behaviour under test ever runs. Tests that care about the current
        // theme's id or stylesheet override this with their own stub.
        WeblogTheme defaultCurrentTheme = mock(WeblogTheme.class);
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(defaultCurrentTheme);
    }

    // --- execute ---

    @Test
    void executeOnACustomThemeLeavesTheThemeIdsUnsetAndSkipsImport() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);

        String view = controller.execute(request, model);

        assertEquals(".ThemeEdit", view);
        assertTrue(model.containsAttribute("themeId"));
        assertNull(model.getAttribute("themeId"));
        assertNull(model.getAttribute("selectedThemeId"));
        assertEquals(Boolean.FALSE, model.getAttribute("importTheme"));
    }

    @Test
    void executeOnASharedThemePopulatesTheThemeIdFromTheCurrentTheme() throws Exception {
        weblog.setEditorTheme("shared-1");
        WeblogTheme currentTheme = mock(WeblogTheme.class);
        when(currentTheme.getId()).thenReturn("shared-1");
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(currentTheme);

        controller.execute(request, model);

        assertEquals("shared-1", model.getAttribute("themeId"));
        assertEquals("shared-1", model.getAttribute("selectedThemeId"));
    }

    @Test
    void executeAlwaysPopulatesTheEnabledThemesListFromTheThemeManager() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);
        SharedTheme theme = mock(SharedTheme.class);
        when(weblogger.getThemeManager().getEnabledThemesList()).thenReturn(List.of(theme));

        controller.execute(request, model);

        assertEquals(List.of(theme), model.getAttribute("themes"));
    }

    @Test
    void executeFlagsFirstCustomizationWhenNoWeblogTemplateExistsYet() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);
        when(weblogger.getWeblogManager().getTemplateByAction(weblog, ComponentType.WEBLOG))
                .thenReturn(null);

        controller.execute(request, model);

        assertEquals(Boolean.TRUE, model.getAttribute("firstCustomization"));
    }

    @Test
    void executeDoesNotFlagFirstCustomizationWhenAWeblogTemplateAlreadyExists() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);
        when(weblogger.getWeblogManager().getTemplateByAction(weblog, ComponentType.WEBLOG))
                .thenReturn(new WeblogTemplate());

        controller.execute(request, model);

        assertEquals(Boolean.FALSE, model.getAttribute("firstCustomization"));
    }

    @Test
    void executeLeavesFirstCustomizationAbsentWhenTheLookupFails() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);
        when(weblogger.getWeblogManager().getTemplateByAction(weblog, ComponentType.WEBLOG))
                .thenThrow(new WebloggerException("database down"));

        controller.execute(request, model);

        assertFalse(model.containsAttribute("firstCustomization"),
                "The lookup exception happens while evaluating the addAttribute argument, so the "
                        + "attribute must never be set at all, not merely null");
    }

    // --- save: themeType=custom ---

    @Test
    void saveToCustomWithoutImportSetsTheThemeAndReportsSuccess() throws Exception {
        weblog.setEditorTheme("shared-1");

        String view = controller.save(request, model, WeblogTheme.CUSTOM, null, false);

        assertEquals(".ThemeEdit", view);
        assertEquals(WeblogTheme.CUSTOM, weblog.getEditorTheme());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(weblogger.flushCount() > 0);
        assertEquals(List.of("themeEditor.setTheme.success", "themeEditor.setCustomTheme.instructions"),
                messages(model));
    }

    @Test
    void saveToCustomWithImportPullsInTheSharedThemeAndReportsSuccess() throws Exception {
        weblog.setEditorTheme("shared-1");
        SharedTheme sourceTheme = mock(SharedTheme.class);
        when(sourceTheme.getName()).thenReturn("Rounders 3.0");
        when(weblogger.getThemeManager().getTheme("shared-1")).thenReturn(sourceTheme);
        registerMessage("themeEditor.setCustomTheme.success", "imported:{0}");

        String view = controller.save(request, model, WeblogTheme.CUSTOM, "shared-1", true);

        assertEquals(".ThemeEdit", view);
        verify(weblogger.getThemeManager()).importTheme(weblog, sourceTheme, false);
        assertTrue(messages(model).contains("imported:Rounders 3.0"), "Got: " + messages(model));
        assertEquals(WeblogTheme.CUSTOM, weblog.getEditorTheme());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void saveToCustomSkipsTheStylesheetWhenImportingTheSameThemeThatHadACustomStylesheetOverride() throws Exception {
        // sharedThemeCustomStylesheet is computed from the weblog's state as
        // save() is entered, before editorTheme is reassigned to CUSTOM below.
        weblog.setEditorTheme("shared-1");
        ThemeTemplate stylesheetTemplate = mock(ThemeTemplate.class);
        when(stylesheetTemplate.getLink()).thenReturn("style.css");
        WeblogTheme currentTheme = mock(WeblogTheme.class);
        when(currentTheme.getStylesheet()).thenReturn(stylesheetTemplate);
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(currentTheme);
        when(weblogger.getWeblogManager().getTemplateByLink(weblog, "style.css"))
                .thenReturn(new WeblogTemplate());

        SharedTheme sourceTheme = mock(SharedTheme.class);
        when(sourceTheme.getName()).thenReturn("Rounders 3.0");
        when(weblogger.getThemeManager().getTheme("shared-1")).thenReturn(sourceTheme);

        controller.save(request, model, WeblogTheme.CUSTOM, "shared-1", true);

        verify(weblogger.getThemeManager()).importTheme(weblog, sourceTheme, true);
    }

    @Test
    void saveToCustomWithImportButNoSelectedThemeIdSkipsTheImport() throws Exception {
        weblog.setEditorTheme("shared-1");

        controller.save(request, model, WeblogTheme.CUSTOM, "", true);

        verify(weblogger.getThemeManager(), never()).importTheme(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertEquals(WeblogTheme.CUSTOM, weblog.getEditorTheme(),
                "With no theme selected the import is skipped but the weblog still switches to CUSTOM");
    }

    @Test
    void saveToCustomStopsBeforeSavingWhenTheImportFails() throws Exception {
        weblog.setEditorTheme("shared-1");
        SharedTheme sourceTheme = mock(SharedTheme.class);
        when(weblogger.getThemeManager().getTheme("shared-1")).thenReturn(sourceTheme);
        doThrow(new RuntimeException("import blew up"))
                .when(weblogger.getThemeManager()).importTheme(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

        String view = controller.save(request, model, WeblogTheme.CUSTOM, "shared-1", true);

        assertEquals(".ThemeEdit", view);
        assertTrue(errors(model).contains("generic.error.check.logs"), "Got: " + errors(model));
        assertEquals("shared-1", weblog.getEditorTheme(),
                "A failed import must return early and never reach the CUSTOM reassignment/save");
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    // --- save: themeType=shared ---

    @Test
    void saveToADifferentSharedThemeSavesAndReportsSuccess() throws Exception {
        weblog.setEditorTheme("shared-1");
        SharedTheme newTheme = mock(SharedTheme.class);
        when(newTheme.getName()).thenReturn("Blogblog");
        when(weblogger.getThemeManager().getTheme("shared-2")).thenReturn(newTheme);
        registerMessage("themeEditor.setTheme.success", "now:{0}");

        String view = controller.save(request, model, "shared", "shared-2", false);

        assertEquals(".ThemeEdit", view);
        assertEquals("shared-2", weblog.getEditorTheme());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(weblogger.flushCount() > 0);
        assertTrue(messages(model).contains("now:Blogblog"), "Got: " + messages(model));
    }

    @Test
    void saveToTheSameSharedThemeSavesButReportsNoSuccessMessage() throws Exception {
        weblog.setEditorTheme("shared-1");
        SharedTheme sameTheme = mock(SharedTheme.class);
        when(weblogger.getThemeManager().getTheme("shared-1")).thenReturn(sameTheme);

        controller.save(request, model, "shared", "shared-1", false);

        assertEquals("shared-1", weblog.getEditorTheme());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(weblogger.flushCount() > 0,
                "The save/flush must still happen even though the theme did not change");
        assertTrue(messages(model).isEmpty(),
                "Re-selecting the current theme must not report a change that did not happen");
    }

    @Test
    void saveToASharedThemeRemovesAStylesheetOverrideLeftByTheOldTheme() throws Exception {
        weblog.setEditorTheme("shared-1");
        ThemeTemplate stylesheetTemplate = mock(ThemeTemplate.class);
        WeblogTheme currentTheme = mock(WeblogTheme.class);
        when(currentTheme.getStylesheet()).thenReturn(stylesheetTemplate);
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(currentTheme);

        WeblogTemplate overrideTemplate = new WeblogTemplate();
        when(weblogger.getWeblogManager().getTemplateByAction(weblog, ComponentType.STYLESHEET))
                .thenReturn(overrideTemplate);

        SharedTheme newTheme = mock(SharedTheme.class);
        when(weblogger.getThemeManager().getTheme("shared-2")).thenReturn(newTheme);

        controller.save(request, model, "shared", "shared-2", false);

        verify(weblogger.getWeblogManager()).removeTemplate(overrideTemplate);
    }

    @Test
    void saveToASharedThemeReportsThemeNotFoundAndSkipsTheSaveEntirely() throws Exception {
        weblog.setEditorTheme("shared-1");
        when(weblogger.getThemeManager().getTheme("missing"))
                .thenThrow(new WebloggerException("no such theme"));

        controller.save(request, model, "shared", "missing", false);

        assertTrue(errors(model).contains("Theme not found"), "Got: " + errors(model));
        assertEquals("shared-1", weblog.getEditorTheme(),
                "A theme lookup failure must leave the weblog's theme untouched");
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void saveAlwaysReloadsThemeDataAndRepopulatesTheFormRegardlessOfOutcome() throws Exception {
        weblog.setEditorTheme("shared-1");
        WeblogTheme currentTheme = mock(WeblogTheme.class);
        when(currentTheme.getId()).thenReturn("shared-1");
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(currentTheme);
        when(weblogger.getThemeManager().getTheme("missing"))
                .thenThrow(new WebloggerException("no such theme"));

        String view = controller.save(request, model, "shared", "missing", false);

        assertEquals(".ThemeEdit", view);
        assertTrue(model.containsAttribute("themes"),
                "loadThemeData must run again even on the theme-not-found failure path");
        assertEquals("shared-1", model.getAttribute("selectedThemeId"),
                "The form must be repopulated with the weblog's (unchanged) current theme id");
    }
}

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
package org.apache.roller.weblogger.business.themes;

import java.util.ArrayList;
import java.util.List;

import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Theme lookup, and the import that turns a shared theme into a weblog's own
 * templates.
 *
 * <p>{@code importTheme} is the most destructive operation in the admin UI and
 * the themes package had no tests at all. It creates or overwrites a template
 * per theme template, <em>deletes</em> any action template the new theme does
 * not define, and finally flips the weblog to {@code CUSTOM} -- one way, with
 * no route back. Everything about it is silent: it reports success either way,
 * and the damage only shows up as a page that stopped rendering.
 *
 * <p>These tests import the <em>real</em> bundled themes against a mocked
 * persistence tier. That combination is deliberate: theme parsing and template
 * enumeration stay honest (a theme.xml that stopped listing a template fails
 * here), while what would have been written is observable without a database.
 */
class ThemeManagerImplTest {

    private MockWeblogger weblogger;
    private ThemeManagerImpl themeManager;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.install();
        themeManager = new ThemeManagerImpl(weblogger.weblogger());
        themeManager.initialize();

        weblog = new Weblog();
        weblog.setHandle("themeblog");
        weblog.setName("Theme Blog");
        weblog.setEditorTheme("basic");

        when(weblogger.mediaFileManager().getDefaultMediaFileDirectory(weblog))
                .thenReturn(new MediaFileDirectory());
    }

    @AfterEach
    void tearDown() {
        MockWeblogger.uninstall();
    }

    // ---------------------------------------------------------------- lookup

    @Test
    void aBundledThemeIsFoundById() throws Exception {
        SharedTheme theme = themeManager.getTheme("basic");

        assertNotNull(theme);
        assertEquals("basic", theme.getId());
    }

    /**
     * An unknown id throws rather than returning null. Callers pass user input
     * here -- the theme chooser posts whatever was selected -- and a null would
     * surface later as an NPE somewhere unrelated.
     */
    @Test
    void anUnknownThemeIdThrows() {
        assertThrows(ThemeNotFoundException.class, () -> themeManager.getTheme("no-such-theme"));
    }

    @Test
    void everyBundledThemeIsLoadedAndSortedByName() {
        List<SharedTheme> themes = themeManager.getEnabledThemesList();

        List<String> ids = themes.stream().map(SharedTheme::getId).toList();
        assertTrue(ids.containsAll(List.of("basic", "portfolio", "travel", "gaurav", "fauxcoly")),
                "every theme shipped in the themes directory must be offered: " + ids);

        List<String> names = themes.stream().map(SharedTheme::getName).toList();
        List<String> sorted = new ArrayList<>(names);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, names, "the chooser relies on this list already being in order");
    }

    // ------------------------------------------------- resolving a weblog's theme

    @Test
    void aWeblogOnASharedThemeGetsASharedThemeView() throws Exception {
        WeblogTheme resolved = themeManager.getTheme(weblog);

        assertInstanceOf(WeblogSharedTheme.class, resolved);
    }

    @Test
    void aWeblogOnACustomThemeGetsACustomThemeView() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);

        assertInstanceOf(WeblogCustomTheme.class, themeManager.getTheme(weblog));
    }

    /**
     * A weblog with no theme recorded at all is treated as custom rather than
     * left without one, so its own templates still render.
     */
    @Test
    void aWeblogWithNoThemeRecordedIsTreatedAsCustom() throws Exception {
        weblog.setEditorTheme(null);

        assertInstanceOf(WeblogCustomTheme.class, themeManager.getTheme(weblog));
    }

    @Test
    void aNullWeblogHasNoTheme() throws Exception {
        assertNull(themeManager.getTheme((Weblog) null));
    }

    /**
     * A weblog naming a theme that is no longer on disk -- removed between
     * releases -- resolves to nothing rather than to some other theme.
     */
    @Test
    void aWeblogNamingAThemeThatNoLongerExistsResolvesToNothing() throws Exception {
        weblog.setEditorTheme("deleted-theme");

        assertNull(themeManager.getTheme(weblog));
    }

    // ---------------------------------------------------------------- import

    @Test
    void importingCopiesEveryThemeTemplateOntoTheWeblog() throws Exception {
        SharedTheme basic = themeManager.getTheme("basic");

        themeManager.importTheme(weblog, basic, false);

        ArgumentCaptor<WeblogTemplate> saved = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogger.weblogManager(), atLeastOnce()).saveTemplate(saved.capture());

        List<String> names = saved.getAllValues().stream().map(WeblogTemplate::getName).toList();
        List<String> expected = basic.getTemplates().stream()
                .map(org.apache.roller.weblogger.pojos.ThemeTemplate::getName).toList();
        assertTrue(names.containsAll(expected),
                "every template the theme defines must be written to the weblog. expected "
                        + expected + " got " + names);
        saved.getAllValues().forEach(template ->
                assertEquals(weblog, template.getWeblog(),
                        "each copied template must belong to the importing weblog"));
    }

    /**
     * The point of no return. Nothing else in the admin UI changes a weblog's
     * theme to CUSTOM, and once it is there the weblog stops tracking the
     * shared theme entirely.
     */
    @Test
    void importingSwitchesTheWeblogToCustomAndSavesIt() throws Exception {
        themeManager.importTheme(weblog, themeManager.getTheme("basic"), false);

        assertEquals(WeblogTheme.CUSTOM, weblog.getEditorTheme());
        verify(weblogger.weblogManager()).saveWeblog(weblog);
    }

    /**
     * Importing overwrites in place rather than duplicating: a weblog that
     * already has a Weblog template gets that one updated, not a second one.
     */
    @Test
    void anExistingActionTemplateIsUpdatedRatherThanDuplicated() throws Exception {
        WeblogTemplate existing = new WeblogTemplate();
        existing.setWeblog(weblog);
        existing.setName("Weblog");
        existing.setAction(ComponentType.WEBLOG);
        when(weblogger.weblogManager().getTemplateByAction(weblog, ComponentType.WEBLOG))
                .thenReturn(existing);

        themeManager.importTheme(weblog, themeManager.getTheme("basic"), false);

        ArgumentCaptor<WeblogTemplate> saved = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogger.weblogManager(), atLeastOnce()).saveTemplate(saved.capture());
        long weblogTemplates = saved.getAllValues().stream()
                .filter(t -> ComponentType.WEBLOG.equals(t.getAction())).count();
        assertEquals(1, weblogTemplates,
                "the existing template must be reused, not joined by a second one");
        assertTrue(saved.getAllValues().contains(existing),
                "and it must be the very object that was already there");
    }

    /**
     * The destructive half. An action template the weblog carries from a
     * previous theme, which the incoming theme does not define, is deleted --
     * otherwise a stale page would keep rendering under the new theme.
     */
    @Test
    void anActionTemplateTheNewThemeDoesNotDefineIsDeleted() throws Exception {
        WeblogTemplate stale = new WeblogTemplate();
        stale.setWeblog(weblog);
        stale.setName("Tag Index");
        stale.setAction(ComponentType.TAGSINDEX);
        when(weblogger.weblogManager().getTemplateByAction(weblog, ComponentType.TAGSINDEX))
                .thenReturn(stale);

        themeManager.importTheme(weblog, themeManager.getTheme("basic"), false);

        verify(weblogger.weblogManager()).removeTemplate(stale);
    }

    /**
     * Deletion is scoped to action templates. A CUSTOM template is the
     * author's own page -- {@code TemplateIT} covers one being served at
     * {@code /<handle>/page/<link>} -- and an import must not take it with it.
     */
    @Test
    void customTemplatesAreNeverDeletedByAnImport() throws Exception {
        themeManager.importTheme(weblog, themeManager.getTheme("basic"), false);

        verify(weblogger.weblogManager(), never())
                .getTemplateByAction(any(), eq(ComponentType.CUSTOM));
    }

    /**
     * {@code skipStylesheet} is what lets a weblog re-import a theme it has
     * already customised without losing its own stylesheet edits. The
     * controller passes it exactly when the weblog is re-importing the theme it
     * is already on.
     */
    @Test
    void skipStylesheetLeavesAnExistingStylesheetUntouched() throws Exception {
        SharedTheme basic = themeManager.getTheme("basic");
        org.apache.roller.weblogger.pojos.ThemeTemplate themeStylesheet = basic.getStylesheet();
        assertNotNull(themeStylesheet, "the basic theme is expected to ship a stylesheet");

        WeblogTemplate customised = new WeblogTemplate();
        customised.setWeblog(weblog);
        customised.setName(themeStylesheet.getName());
        customised.setAction(ComponentType.STYLESHEET);
        customised.setDescription("edited by the author");
        when(weblogger.weblogManager().getTemplateByAction(weblog, ComponentType.STYLESHEET))
                .thenReturn(customised);

        themeManager.importTheme(weblog, basic, true);

        assertEquals("edited by the author", customised.getDescription(),
                "the author's stylesheet must survive a re-import");

        ArgumentCaptor<WeblogTemplate> saved = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogger.weblogManager(), atLeastOnce()).saveTemplate(saved.capture());
        assertFalse(saved.getAllValues().contains(customised),
                "and must not be written back over");
    }

    /** Without the flag, the same re-import does overwrite the stylesheet. */
    @Test
    void withoutSkipStylesheetTheStylesheetIsOverwritten() throws Exception {
        SharedTheme basic = themeManager.getTheme("basic");
        WeblogTemplate customised = new WeblogTemplate();
        customised.setWeblog(weblog);
        customised.setName(basic.getStylesheet().getName());
        customised.setAction(ComponentType.STYLESHEET);
        customised.setDescription("edited by the author");
        when(weblogger.weblogManager().getTemplateByAction(weblog, ComponentType.STYLESHEET))
                .thenReturn(customised);

        themeManager.importTheme(weblog, basic, false);

        ArgumentCaptor<WeblogTemplate> saved = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogger.weblogManager(), atLeastOnce()).saveTemplate(saved.capture());
        assertTrue(saved.getAllValues().contains(customised),
                "with the flag off the theme's stylesheet replaces the author's");
    }

    /**
     * A weblog with no media directory yet must still import. The manager logs
     * it and carries on -- refusing would make the theme unusable for a weblog
     * that has never uploaded anything.
     */
    @Test
    void importingSurvivesAWeblogWithNoMediaDirectory() throws Exception {
        when(weblogger.mediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(null);

        themeManager.importTheme(weblog, themeManager.getTheme("basic"), false);

        assertEquals(WeblogTheme.CUSTOM, weblog.getEditorTheme());
    }
}

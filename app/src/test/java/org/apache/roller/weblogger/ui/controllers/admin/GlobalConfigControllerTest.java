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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.config.runtime.ConfigDef;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link GlobalConfigController}, the admin settings page.
 *
 * <p>The page edits the live {@code roller_properties} rows, so the interesting
 * question at every turn is whether a given submission actually reaches
 * {@code saveProperties} and with what value. A rejected form that saves anyway
 * would silently change the running site.
 */
class GlobalConfigControllerTest {

    private MockWeblogger weblogger;
    private GlobalConfigController controller;
    private ExtendedModelMap model;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.install();
        controller = ControllerTestFixture.withMessages(new GlobalConfigController());
        model = new ExtendedModelMap();
    }

    @AfterEach
    void tearDown() {
        MockWeblogger.uninstall();
    }

    // --- display ---

    @Test
    void theSettingsPageIsAdminOnlyAndNeedsNoWeblog() {
        // RollerHandlerInterceptor enforces this before the handler runs; it is
        // what keeps site-wide settings away from ordinary bloggers.
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
        assertEquals("configForm.title", controller.getPageTitle());
        assertEquals("admin", controller.getDesiredMenu());
        assertEquals("globalConfig", controller.getActionName());
    }

    @Test
    void theFormShowsTheStoredPropertiesAlongsideTheirDefinitions() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.name", "My Site");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        Weblog weblog = new Weblog();
        when(weblogger.weblogManager().getWeblogs(anyBoolean(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(weblog));

        String view = controller.execute(request(), model);

        assertEquals(".GlobalConfig", view);
        assertEquals("globalConfig", model.getAttribute("actionName"));
        assertEquals("admin", model.getAttribute("desiredMenu"));
        assertSame(stored, model.getAttribute("properties"));
        assertEquals(List.of(weblog), model.getAttribute("weblogs"),
                "the front-page weblog picker needs the weblog list");
        ConfigDef configDef = (ConfigDef) model.getAttribute("globalConfigDef");
        assertNotNull(configDef, "without the config definitions the page has nothing to render");
        assertEquals("global-properties", configDef.getName());
        assertNotNull(configDef.getPropertyDef("site.name"));
    }

    @Test
    void theFormStillRendersWhenThePropertiesCannotBeRead() throws Exception {
        // A broken database read must not take out the one page an admin might
        // use to fix the configuration.
        when(weblogger.propertiesManager().getProperties()).thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request(), model);

        assertEquals(".GlobalConfig", view);
        assertTrue(((Map<?, ?>) model.getAttribute("properties")).isEmpty());
    }

    @Test
    void theEnabledCommentPluginsAreSplitOutOfTheStoredCsv() throws Exception {
        when(weblogger.propertiesManager().getProperty("users.comments.plugins"))
                .thenReturn(new RuntimeConfigProperty("users.comments.plugins", "ConvertLineBreaks,TextileFormatter"));

        controller.execute(request(), model);

        assertArrayEquals(new String[]{"ConvertLineBreaks", "TextileFormatter"},
                (String[]) model.getAttribute("commentPlugins"));
    }

    @Test
    void noCommentPluginsConfiguredMeansNoneAreTicked() throws Exception {
        when(weblogger.propertiesManager().getProperty("users.comments.plugins"))
                .thenReturn(new RuntimeConfigProperty("users.comments.plugins", ""));

        controller.execute(request(), model);

        assertArrayEquals(new String[0], (String[]) model.getAttribute("commentPlugins"));
    }

    // --- saving ---

    @Test
    void aSubmittedStringValueIsTrimmedAndSaved() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.name", "Old Name");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.name")).thenReturn("  New Name  ");

        String view = controller.save(request, model);

        assertEquals(".GlobalConfig", view);
        assertEquals("New Name", stored.get("site.name").getValue(),
                "leading and trailing whitespace in a site name is never intentional");
        verify(weblogger.propertiesManager()).saveProperties(stored);
        verify(weblogger.weblogger()).flush();
        assertEquals(List.of("generic.changes.saved"), ControllerTestFixture.messages(model));
        assertEquals("admin", model.getAttribute("desiredMenu"),
                "the settings page is re-rendered after saving and still needs its menu");
    }

    @Test
    void anUntickedCheckboxTurnsThePropertyOff() throws Exception {
        // Browsers send nothing at all for an unticked checkbox, so "absent"
        // has to mean false -- otherwise a setting could never be turned off.
        Map<String, RuntimeConfigProperty> stored = properties("users.comments.enabled", "true");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);

        controller.save(request(), model);

        assertEquals("false", stored.get("users.comments.enabled").getValue());
        verify(weblogger.propertiesManager()).saveProperties(stored);
    }

    @Test
    void aTickedCheckboxTurnsThePropertyOn() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("users.comments.enabled", "false");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("users.comments.enabled")).thenReturn("true");

        controller.save(request, model);

        assertEquals("true", stored.get("users.comments.enabled").getValue());
    }

    @Test
    void anIntegerFieldWithLettersInItIsRejectedAndNothingIsSaved() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.pages.maxEntries", "30");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.pages.maxEntries")).thenReturn("many");

        String view = controller.save(request, model);

        assertEquals(".GlobalConfig", view);
        assertEquals(1, ControllerTestFixture.errors(model).size());
        assertTrue(ControllerTestFixture.errors(model).get(0).startsWith("ConfigForm.invalidIntegerProperty["),
                "the admin has to be told which property was wrong: " + ControllerTestFixture.errors(model));
        assertEquals("30", stored.get("site.pages.maxEntries").getValue(), "the stored value must be left alone");
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
        verify(weblogger.weblogger(), never()).flush();
    }

    @Test
    void anIntegerFieldWithANumberInItIsAccepted() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("site.pages.maxEntries", "30");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("site.pages.maxEntries")).thenReturn("45");

        controller.save(request, model);

        assertEquals("45", stored.get("site.pages.maxEntries").getValue());
        assertEquals(List.of(), ControllerTestFixture.errors(model));
    }

    @Test
    void aFloatFieldWithLettersInItIsRejectedAndNothingIsSaved() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("uploads.file.maxsize", "2.00");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("uploads.file.maxsize")).thenReturn("big");

        String view = controller.save(request, model);

        assertEquals(".GlobalConfig", view);
        assertEquals(1, ControllerTestFixture.errors(model).size());
        assertTrue(ControllerTestFixture.errors(model).get(0).startsWith("ConfigForm.invalidFloatProperty["),
                "the admin has to be told which property was wrong: " + ControllerTestFixture.errors(model));
        assertEquals("2.00", stored.get("uploads.file.maxsize").getValue(), "the stored value must be left alone");
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void aFloatFieldWithADecimalInItIsAccepted() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("uploads.file.maxsize", "2.00");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameter("uploads.file.maxsize")).thenReturn("3.5");

        controller.save(request, model);

        assertEquals("3.5", stored.get("uploads.file.maxsize").getValue());
        assertEquals(List.of(), ControllerTestFixture.errors(model));
    }

    @Test
    void aTextFieldMissingFromTheSubmissionIsReportedRatherThanBlanked() throws Exception {
        // Every property on the form should come back with the form. One that
        // does not means the page and the definitions have drifted apart, and
        // silently clearing a site setting would be worse than saying so.
        Map<String, RuntimeConfigProperty> stored = properties("site.name", "My Site");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);

        String view = controller.save(request(), model);

        assertEquals(".GlobalConfig", view);
        assertEquals(List.of("ConfigForm.invalidProperty[site.name]"), ControllerTestFixture.errors(model));
        assertEquals("My Site", stored.get("site.name").getValue());
        verify(weblogger.propertiesManager(), never()).saveProperties(any());
    }

    @Test
    void anUnknownPropertyInTheDatabaseIsLeftUntouched() throws Exception {
        // Rows with no definition in runtimeConfigDefs.xml are not on the form,
        // so the form's silence about them must not be read as "clear it".
        Map<String, RuntimeConfigProperty> stored = properties("some.legacy.property", "keep me");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);

        controller.save(request(), model);

        assertEquals("keep me", stored.get("some.legacy.property").getValue());
        assertEquals(List.of(), ControllerTestFixture.errors(model));
    }

    @Test
    void theTickedCommentPluginsAreStoredAsACsv() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("users.comments.plugins", "");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        HttpServletRequest request = request();
        when(request.getParameterValues("commentPlugins"))
                .thenReturn(new String[]{"ConvertLineBreaks", "TextileFormatter"});

        controller.save(request, model);

        assertEquals("ConvertLineBreaks,TextileFormatter", stored.get("users.comments.plugins").getValue());
        verify(weblogger.propertiesManager()).saveProperties(stored);
    }

    @Test
    void untickingEveryCommentPluginClearsTheProperty() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("users.comments.plugins", "TextileFormatter");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);

        controller.save(request(), model);

        assertEquals("", stored.get("users.comments.plugins").getValue());
    }

    @Test
    void aFailedWriteIsReportedInsteadOfBeingAnnouncedAsSaved() throws Exception {
        Map<String, RuntimeConfigProperty> stored = properties("users.comments.plugins", "");
        when(weblogger.propertiesManager().getProperties()).thenReturn(stored);
        doThrow(new WebloggerException("database down"))
                .when(weblogger.propertiesManager()).saveProperties(any());

        String view = controller.save(request(), model);

        assertEquals(".GlobalConfig", view);
        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
        assertEquals(List.of(), ControllerTestFixture.messages(model));
    }

    // --- helpers ---

    /** A properties map as {@code PropertiesManager} would return it. */
    private static Map<String, RuntimeConfigProperty> properties(String name, String value) {
        Map<String, RuntimeConfigProperty> props = new LinkedHashMap<>();
        props.put(name, new RuntimeConfigProperty(name, value));
        if (!"users.comments.plugins".equals(name)) {
            // save() always writes this one, so every fixture needs it present.
            props.put("users.comments.plugins", new RuntimeConfigProperty("users.comments.plugins", ""));
        }
        return props;
    }

    private HttpServletRequest request() {
        HttpServletRequest request = ControllerTestFixture.requestFor(new User());
        when(request.getParameter(anyString())).thenReturn(null);
        return request;
    }
}

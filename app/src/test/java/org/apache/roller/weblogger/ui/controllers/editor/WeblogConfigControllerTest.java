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

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WeblogConfigController} and {@link WeblogConfigBean}.
 *
 * <p>This form writes settings that govern the blog's public behaviour, so the
 * checks that matter are the ones that stop a setting from being applied: the
 * entry-display cap (a blog asking for 10,000 entries per page would render
 * itself into a timeout) and the language invariant the controller enforces
 * after copying the form onto the weblog.
 */
class WeblogConfigControllerTest extends EditorControllerTestSupport {

    private WeblogConfigController controller;
    private WeblogConfigBean bean;
    private Model model;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new WeblogConfigController());
        bean = new WeblogConfigBean();
        model = newModel();

        // The display-count cap is a runtime property; unstubbed it reads as -1,
        // which would reject every possible value.
        givenRuntimeProperty("site.pages.maxEntries", "30");

        weblog.setActive(Boolean.TRUE);
        bean.copyFrom(weblog);
    }

    // --- viewing ---

    @Test
    void openingTheFormLoadsTheWeblogsCurrentSettings() {
        weblog.setName("My Blog");
        weblog.setTagline("A tagline");
        weblog.setEntryDisplayCount(25);

        String view = controller.execute(request, model, bean);

        assertEquals(".WeblogConfig", view);
        assertEquals("My Blog", bean.getName());
        assertEquals("A tagline", bean.getTagline());
        assertEquals(25, bean.getEntryDisplayCount());
        assertEquals(WEBLOG_HANDLE, bean.getHandle());
    }

    // --- saving ---

    @Test
    void savingAppliesTheFormToTheWeblogAndPersistsIt() throws Exception {
        bean.setName("Renamed Blog");
        bean.setTagline("New tagline");
        bean.setEmailAddress("owner@example.com");
        bean.setEntryDisplayCount(20);

        String view = controller.save(request, model, bean);

        assertEquals(".WeblogConfig", view);
        assertEquals("Renamed Blog", weblog.getName());
        assertEquals("New tagline", weblog.getTagline());
        assertEquals("owner@example.com", weblog.getEmailAddress());
        assertEquals(20, weblog.getEntryDisplayCount());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(messages(model).contains("websiteSettings.savedChanges"),
                "Expected a save confirmation, got: " + messages(model));
    }

    @Test
    void savingNeverChangesTheWeblogHandle() throws Exception {
        // The handle is the blog's URL and its identity in every permission
        // row; copyTo deliberately does not carry it across.
        bean.setHandle("hijacked");

        controller.save(request, model, bean);

        assertEquals(WEBLOG_HANDLE, weblog.getHandle(),
                "A posted handle must not be able to rename the blog out from under its URLs");
    }

    @Test
    void anEntryDisplayCountAboveTheSiteCapIsRefused() throws Exception {
        // Beyond the cap the front page renders unboundedly many entries.
        bean.setEntryDisplayCount(500);

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.error.entryDisplayCount"),
                "Expected an entry-display-count error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void anEntryDisplayCountExactlyAtTheSiteCapIsAccepted() throws Exception {
        // The check is strictly greater-than; the cap itself must be usable.
        bean.setEntryDisplayCount(30);

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "The cap itself must be allowed: " + errors(model));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aFailedSaveIsReportedRatherThanConfirmed() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogManager()).saveWeblog(any());

        controller.save(request, model, bean);

        assertFalse(messages(model).contains("websiteSettings.savedChanges"),
                "A failed save must not report success");
        assertTrue(errors(model).size() >= 1,
                "Expected the failure to be surfaced, got: " + errors(model));
    }

    // --- custom domain ---

    private String previousVhostCertZones;

    @AfterEach
    void restoreVhostCertZones() {
        // myValidate reads this straight off WebloggerConfig, which is
        // process-global, so any test that overrides it must put it back.
        if (previousVhostCertZones != null) {
            overrideConfigProperty("vhost.cert.zones", previousVhostCertZones);
            previousVhostCertZones = null;
        }
    }

    @Test
    void blankCustomDomainIsAcceptedAndClearsTheWeblog() throws Exception {
        weblog.setCustomDomain("old.example.com");
        bean.setCustomDomain("  ");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertNull(weblog.getCustomDomain());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aWellFormedCustomDomainIsNormalisedAndSaved() throws Exception {
        bean.setCustomDomain("  VHost.Example.COM ");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertEquals("vhost.example.com", weblog.getCustomDomain());
        assertEquals("vhost.example.com", bean.getCustomDomain(),
                "The bean re-rendered on the response must show the normalised value too");
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aMalformedCustomDomainIsRejected() throws Exception {
        bean.setCustomDomain("not a hostname");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.customDomain.invalid"),
                "Expected an invalid-domain error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void aCustomDomainAlreadyClaimedByAnotherWeblogIsRejected() throws Exception {
        Weblog other = new Weblog();
        other.setHandle("otherblog");
        when(weblogger.getWeblogManager().getWeblogByCustomDomain("taken.example.com"))
                .thenReturn(other);
        bean.setCustomDomain("taken.example.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.customDomain.taken"),
                "Expected a taken-domain error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    /**
     * Re-saving a weblog's own unchanged domain must not trip the uniqueness
     * check against itself.
     */
    @Test
    void reSavingThisWeblogsOwnCustomDomainIsNotAConflict() throws Exception {
        Weblog self = new Weblog();
        self.setHandle(WEBLOG_HANDLE);
        when(weblogger.getWeblogManager().getWeblogByCustomDomain("mine.example.com"))
                .thenReturn(self);
        bean.setCustomDomain("mine.example.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aWebloggerExceptionDuringTheUniquenessCheckIsReportedAsInvalid() throws Exception {
        when(weblogger.getWeblogManager().getWeblogByCustomDomain("vhost.example.com"))
                .thenThrow(new WebloggerException("database down"));
        bean.setCustomDomain("vhost.example.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.customDomain.invalid"),
                "Expected the lookup failure to surface as an invalid-domain error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void aCustomDomainOutsideConfiguredZonesWarnsButStillSaves() throws Exception {
        previousVhostCertZones = overrideConfigProperty("vhost.cert.zones", "thelocalwiki.com");
        bean.setCustomDomain("maiiavorobiova.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "A zone mismatch must warn, not block: " + errors(model));
        assertEquals("maiiavorobiova.com", model.getAttribute("customDomainWarning"));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aCustomDomainInsideAConfiguredZoneDoesNotWarn() throws Exception {
        previousVhostCertZones = overrideConfigProperty("vhost.cert.zones", "thelocalwiki.com");
        bean.setCustomDomain("berlin.thelocalwiki.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertNull(model.getAttribute("customDomainWarning"));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    // --- WeblogConfigBean ---

    /**
     * Multi-locale weblogs are gone: {@code WeblogConfigBean} must carry no
     * field for either the "publish in multiple languages" toggle or the
     * "show all languages" toggle that used to sit beside it.
     */
    @Test
    void multiLocaleFieldsAreGone() {
        List<String> offenders = Arrays.stream(WeblogConfigBean.class.getDeclaredFields())
                .map(Field::getName)
                .filter(n -> n.contains("MultiLang") || n.contains("AllLangs"))
                .toList();
        assertTrue(offenders.isEmpty(), "multi-locale fields survive: " + offenders);
    }

    @Test
    void beanRoundTripsTheSettingsItOwns() {
        Weblog source = new Weblog();
        source.setHandle("source");
        source.setName("Source Blog");
        source.setTagline("Tag");
        source.setEmailAddress("a@example.com");
        source.setLocale("fr_FR");
        source.setTimeZone("Asia/Tokyo");
        source.setEntryDisplayCount(7);
        source.setAbout("About me");
        source.setIconPath("icon.png");
        source.setActive(Boolean.TRUE);
        source.setCustomDomain("vhost.example.com");

        WeblogConfigBean copy = new WeblogConfigBean();
        copy.copyFrom(source);

        assertEquals("source", copy.getHandle());
        assertEquals("Source Blog", copy.getName());
        assertEquals("fr_FR", copy.getLocale());
        assertEquals("Asia/Tokyo", copy.getTimeZone());
        assertEquals(7, copy.getEntryDisplayCount());
        assertEquals("About me", copy.getAbout());
        assertEquals("icon.png", copy.getIcon());
        assertEquals("vhost.example.com", copy.getCustomDomain());

        Weblog target = new Weblog();
        copy.copyTo(target);

        assertEquals("Source Blog", target.getName());
        assertEquals("Asia/Tokyo", target.getTimeZone());
        assertEquals("vhost.example.com", target.getCustomDomain());
        assertNull(target.getHandle(), "copyTo must never write the handle");
    }

}

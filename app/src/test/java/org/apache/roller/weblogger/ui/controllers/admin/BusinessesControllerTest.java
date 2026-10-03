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

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.BusinessManager;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.Business;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Businesses admin screen: list, edit, save, delete. */
class BusinessesControllerTest {

    private MockWeblogger weblogger;
    private BusinessManager manager;
    private BusinessesController controller;
    private ExtendedModelMap model;
    private RedirectAttributes redirect;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        manager = weblogger.businessManager();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new BusinessesController());
        model = new ExtendedModelMap();
        redirect = new RedirectAttributesModelMap();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    private static BusinessBean validBean() {
        BusinessBean bean = new BusinessBean();
        bean.setName("Casa Azul");
        bean.setBusinessType("LocalBusiness");
        bean.setWebsiteUrl("https://casa.com");
        bean.setBookingUrl("https://book.com/x?ref=a#dates");
        bean.setTelephone("+351 912 345.678");
        bean.setEmail("hi@casa.com");
        bean.setLogoUrl("https://casa.com/logo.png");
        bean.setSameAs("https://a.com\n  \nhttps://b.com");
        bean.setAreaServed("Sao Miguel");
        bean.setDescription("A guest house.");
        return bean;
    }

    private String save(BusinessBean bean) {
        return controller.save(ControllerTestFixture.requestFor(user()), model, redirect, bean);
    }

    private void assertRefused(BusinessBean bean, String fieldId) throws WebloggerException {
        String view = save(bean);
        assertEquals(".BusinessEdit", view);
        assertTrue(ControllerTestFixture.invalidFields(model).contains(fieldId),
                "expected " + fieldId + " in " + ControllerTestFixture.invalidFields(model));
        verify(manager, never()).saveBusiness(any());
    }

    @Test
    void listShowsEveryBusinessWithItsUsageCount() throws Exception {
        Business b = new Business();
        b.setName("A");
        when(manager.getBusinesses()).thenReturn(List.of(b));
        when(manager.countWeblogsUsing(b)).thenReturn(3L);

        String view = controller.execute(ControllerTestFixture.requestFor(user()), model);

        assertEquals(".Businesses", view);
        assertEquals(List.of(b), model.getAttribute("businesses"));
        assertEquals(3L, ((java.util.Map<?, ?>) model.getAttribute("usage")).get(b.getId()));
        assertEquals("admin", model.getAttribute("desiredMenu"));
        assertEquals("businesses", model.getAttribute("actionName"));
    }

    @Test
    void listThatCannotLoadSaysSoRatherThanShowingEmpty() throws Exception {
        when(manager.getBusinesses()).thenThrow(new WebloggerException("down"));

        controller.execute(ControllerTestFixture.requestFor(user()), model);

        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
    }

    @Test
    void blankIdOpensAnEmptyFormDefaultingToOrganization() {
        String view = controller.edit(ControllerTestFixture.requestFor(user()), model, "");

        assertEquals(".BusinessEdit", view);
        BusinessBean bean = (BusinessBean) model.getAttribute("bean");
        assertNull(bean.getId());
        assertEquals("Organization", bean.getBusinessType());
    }

    @Test
    void existingIdLoadsTheRecordIntoTheForm() throws Exception {
        Business b = new Business();
        b.setName("A");
        b.setTelephone("123");
        when(manager.getBusiness(b.getId())).thenReturn(b);

        controller.edit(ControllerTestFixture.requestFor(user()), model, b.getId());

        BusinessBean bean = (BusinessBean) model.getAttribute("bean");
        assertEquals(b.getId(), bean.getId());
        assertEquals("A", bean.getName());
        assertEquals("123", bean.getTelephone());
    }

    @Test
    void unknownIdIsAnErrorNotAnEmptyForm() throws Exception {
        String view = controller.edit(ControllerTestFixture.requestFor(user()), model, "nope");

        assertEquals(".Businesses", view);
        assertEquals(List.of("businesses.error.notFound"), ControllerTestFixture.errors(model));
    }

    @Test
    void validSaveStoresAndRedirectsToTheList() throws Exception {
        String view = save(validBean());

        assertEquals("redirect:/roller-ui/admin/businesses.rol", view);
        org.mockito.ArgumentCaptor<Business> saved = org.mockito.ArgumentCaptor.forClass(Business.class);
        verify(manager).saveBusiness(saved.capture());
        Business b = saved.getValue();
        assertEquals("Casa Azul", b.getName());
        assertEquals(Business.BusinessType.LocalBusiness, b.getBusinessType());
        assertEquals("https://a.com\nhttps://b.com", b.getSameAs());
        assertEquals("+351 912 345.678", b.getTelephone());
        verify(weblogger.weblogger()).flush();
    }

    @Test
    void blankOptionalFieldsAreStoredAsNull() throws Exception {
        BusinessBean bean = new BusinessBean();
        bean.setName("Only a name");
        bean.setBusinessType("Organization");
        bean.setWebsiteUrl("  ");
        bean.setSameAs(" \n ");

        assertEquals("redirect:/roller-ui/admin/businesses.rol", save(bean));

        org.mockito.ArgumentCaptor<Business> saved = org.mockito.ArgumentCaptor.forClass(Business.class);
        verify(manager).saveBusiness(saved.capture());
        assertNull(saved.getValue().getWebsiteUrl());
        assertNull(saved.getValue().getSameAs());
        assertNull(saved.getValue().getTelephone());
    }

    @Test
    void savingAnExistingBusinessUpdatesThatRecord() throws Exception {
        Business existing = new Business();
        existing.setName("Old");
        when(manager.getBusiness(existing.getId())).thenReturn(existing);
        BusinessBean bean = validBean();
        bean.setId(existing.getId());

        save(bean);

        verify(manager).saveBusiness(existing);
        assertEquals("Casa Azul", existing.getName());
    }

    @Test
    void savingAnUnknownIdIsRefused() throws Exception {
        BusinessBean bean = validBean();
        bean.setId("ghost");

        save(bean);

        assertEquals(List.of("businesses.error.notFound"), ControllerTestFixture.errors(model));
        verify(manager, never()).saveBusiness(any());
    }

    @Test
    void blankNameIsRefused() throws Exception {
        BusinessBean bean = validBean();
        bean.setName("   ");
        assertRefused(bean, "bean_name");
    }

    @Test
    void forgedBusinessTypeIsRefused() throws Exception {
        BusinessBean bean = validBean();
        bean.setBusinessType("Hotel");
        assertRefused(bean, "bean_businessType");
    }

    @Test
    void javascriptWebsiteUrlIsRefused() throws Exception {
        BusinessBean bean = validBean();
        bean.setWebsiteUrl("javascript:alert(1)");
        assertRefused(bean, "bean_websiteUrl");
    }

    @Test
    void badBookingAndLogoUrlsAreRefused() throws Exception {
        BusinessBean bean = validBean();
        bean.setBookingUrl("ftp://x.com");
        bean.setLogoUrl("nope");
        save(bean);
        assertTrue(ControllerTestFixture.invalidFields(model).containsAll(List.of("bean_bookingUrl", "bean_logoUrl")));
        verify(manager, never()).saveBusiness(any());
    }

    @Test
    void badTelephoneAndEmailAreRefused() throws Exception {
        BusinessBean bean = validBean();
        bean.setTelephone("call me");
        bean.setEmail("nope");
        save(bean);
        assertTrue(ControllerTestFixture.invalidFields(model).containsAll(List.of("bean_telephone", "bean_email")));
        verify(manager, never()).saveBusiness(any());
    }

    @Test
    void moreThanTenSameAsLinesAreRefused() throws Exception {
        BusinessBean bean = validBean();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            lines.append("https://p").append(i).append(".com\n");
        }
        bean.setSameAs(lines.toString());
        assertRefused(bean, "bean_sameAs");
    }

    @Test
    void aSameAsLineThatIsNotHttpIsRefusedAndEchoedEscaped() throws Exception {
        BusinessBean bean = validBean();
        bean.setSameAs("https://ok.com\njavascript:<b>x</b>");
        assertRefused(bean, "bean_sameAs");
        String error = ControllerTestFixture.errors(model).get(0);
        assertTrue(error.contains("javascript:&lt;b&gt;x&lt;/b&gt;"), error);
    }

    @Test
    void overlongNameIsRefused() throws Exception {
        BusinessBean bean = validBean();
        bean.setName("x".repeat(256));
        assertRefused(bean, "bean_name");
    }

    @Test
    void aStoreThatFailsOnSaveIsReportedNotSwallowed() throws Exception {
        doThrow(new WebloggerException("down")).when(manager).saveBusiness(any());

        String view = save(validBean());

        assertEquals(".BusinessEdit", view);
        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
    }

    @Test
    void aBusinessInUseCannotBeDeleted() throws Exception {
        Business b = new Business();
        b.setName("<i>Casa</i>");
        when(manager.getBusiness(b.getId())).thenReturn(b);
        when(manager.countWeblogsUsing(b)).thenReturn(2L);

        String view = controller.delete(ControllerTestFixture.requestFor(user()), model, redirect, b.getId());

        assertEquals(".Businesses", view);
        verify(manager, never()).removeBusiness(any());
        String error = ControllerTestFixture.errors(model).get(0);
        assertTrue(error.startsWith("businesses.error.inUse"), error);
        assertTrue(error.contains("&lt;i&gt;Casa&lt;/i&gt;"), "the name is escaped: " + error);
    }

    @Test
    void anUnusedBusinessIsRemovedAndTheListIsShown() throws Exception {
        Business b = new Business();
        b.setName("Casa");
        when(manager.getBusiness(b.getId())).thenReturn(b);
        when(manager.countWeblogsUsing(b)).thenReturn(0L);

        String view = controller.delete(ControllerTestFixture.requestFor(user()), model, redirect, b.getId());

        assertEquals("redirect:/roller-ui/admin/businesses.rol", view);
        verify(manager).removeBusiness(b);
        verify(weblogger.weblogger()).flush();
    }

    @Test
    void aManagerRefusalOnRemoveIsAnErrorNotARedirect() throws Exception {
        Business b = new Business();
        when(manager.getBusiness(b.getId())).thenReturn(b);
        when(manager.countWeblogsUsing(b)).thenReturn(0L);
        doThrow(new WebloggerException("in use")).when(manager).removeBusiness(b);

        String view = controller.delete(ControllerTestFixture.requestFor(user()), model, redirect, b.getId());

        assertEquals(".Businesses", view);
        assertFalse(ControllerTestFixture.errors(model).isEmpty());
    }

    @Test
    void deletingAnUnknownIdIsAnError() throws Exception {
        String view = controller.delete(ControllerTestFixture.requestFor(user()), model, redirect, "ghost");

        assertEquals(".Businesses", view);
        assertEquals(List.of("businesses.error.notFound"), ControllerTestFixture.errors(model));
        verify(manager, never()).removeBusiness(any());
    }

    @Test
    void theScreenIsAdminOnlyAndNeedsNoWeblog() {
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
    }

    private static User user() {
        User user = new User();
        user.setUserName("admin");
        return user;
    }
}

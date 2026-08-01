/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.roller.weblogger.boot;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.function.Consumer;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;

import org.apache.roller.weblogger.ui.controllers.ajax.CommentDataServlet;
import org.apache.roller.weblogger.ui.controllers.ajax.ThemeDataServlet;
import org.apache.roller.weblogger.ui.controllers.ajax.UserDataServlet;
import org.apache.roller.weblogger.ui.core.RollerSession;
import org.apache.roller.weblogger.ui.core.filters.BootstrapFilter;
import org.apache.roller.weblogger.ui.core.filters.CharEncodingFilter;
import org.apache.roller.weblogger.ui.core.filters.IPBanFilter;
import org.apache.roller.weblogger.ui.core.filters.InitFilter;
import org.apache.roller.weblogger.ui.core.filters.PersistenceSessionFilter;
import org.apache.roller.weblogger.ui.core.filters.SpringFirewallExceptionFilter;
import org.apache.roller.weblogger.ui.rendering.filters.RequestMappingFilter;
import org.apache.roller.weblogger.ui.rendering.servlets.CommentAuthenticatorServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.CommentServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.FeedServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.MediaResourceServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.PageServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.PreviewResourceServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.PreviewServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.RSDServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.ResourceServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.SearchServlet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletRegistrationBean;
import org.springframework.web.servlet.DispatcherServlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Every {@code @Bean} method here is a plain, side-effect-free registration
 * builder -- no Spring context is needed to exercise them, just direct calls
 * against a fresh {@link ServletRegistrationConfig}. Pins the transcription
 * from {@code web.xml} the class javadoc describes: URL pattern, {@code
 * loadOnStartup} (via reflection -- {@link ServletRegistrationBean} has no
 * public getter, only the setter web.xml's ordering depends on), filter
 * order, and filter dispatcher types, for every servlet and filter this
 * config class registers.
 */
class ServletRegistrationConfigTest {

    private final ServletRegistrationConfig config = new ServletRegistrationConfig();

    /**
     * {@link ServletRegistrationBean} exposes no getter for {@code
     * loadOnStartup} (only {@code setLoadOnStartup}), so this reads the
     * private field directly -- the same private-field-reflection pattern
     * {@code RenderingTestSupport} already uses elsewhere in this suite.
     */
    private static int loadOnStartupOf(ServletRegistrationBean<?> bean) throws ReflectiveOperationException {
        Field field = ServletRegistrationBean.class.getDeclaredField("loadOnStartup");
        field.setAccessible(true);
        return field.getInt(bean);
    }

    private static void assertServlet(ServletRegistrationBean<?> bean, Class<?> servletType,
            String urlPattern, int expectedLoadOnStartup) throws ReflectiveOperationException {
        assertInstanceOf(servletType, bean.getServlet());
        assertEquals(Set.of(urlPattern), bean.getUrlMappings());
        assertEquals(expectedLoadOnStartup, loadOnStartupOf(bean),
                "loadOnStartup for " + servletType.getSimpleName());
    }

    // ---------------------------------------------------- rendering servlets
    // loadOnStartup values transcribed verbatim from web.xml (see the class
    // javadoc); values below match ServletRegistrationConfig's own comments.

    @Test
    void renderingServletsCarryTheirWebXmlPatternAndLoadOrder() throws Exception {
        assertServlet(config.pageServletRegistration(), PageServlet.class,
                "/roller-ui/rendering/page/*", 5);
        assertServlet(config.feedServletRegistration(), FeedServlet.class,
                "/roller-ui/rendering/feed/*", 5);
        assertServlet(config.resourceServletRegistration(), ResourceServlet.class,
                "/roller-ui/rendering/resources/*", 5);
        assertServlet(config.mediaResourceServletRegistration(), MediaResourceServlet.class,
                "/roller-ui/rendering/media-resources/*", 5);
        assertServlet(config.searchServletRegistration(), SearchServlet.class,
                "/roller-ui/rendering/search/*", 5);
        assertServlet(config.commentServletRegistration(), CommentServlet.class,
                "/roller-ui/rendering/comment/*", 7);
        assertServlet(config.rsdServletRegistration(), RSDServlet.class,
                "/roller-ui/rendering/rsd/*", 7);
        assertServlet(config.commentAuthenticatorServletRegistration(), CommentAuthenticatorServlet.class,
                "/CommentAuthenticatorServlet", 7);
        assertServlet(config.previewServletRegistration(), PreviewServlet.class,
                "/roller-ui/authoring/preview/*", 9);
        assertServlet(config.previewResourceServletRegistration(), PreviewResourceServlet.class,
                "/roller-ui/authoring/previewresource/*", 9);
    }

    @Test
    void ajaxServletsCarryTheirWebXmlPatternAndTheDefaultLoadOrder() throws Exception {
        // No <load-on-startup> element in web.xml for these three -- left at
        // ServletRegistrationBean's own constructor default, -1 (verified via
        // javap against spring-boot-4.1.0.jar: the no-arg and (T, String...)
        // constructors both set loadOnStartup = -1).
        assertServlet(config.commentDataServletRegistration(), CommentDataServlet.class,
                "/roller-ui/authoring/commentdata/*", -1);
        assertServlet(config.userDataServletRegistration(), UserDataServlet.class,
                "/roller-ui/authoring/userdata/*", -1);
        assertServlet(config.themeDataServletRegistration(), ThemeDataServlet.class,
                "/roller-ui/authoring/themedata/*", -1);
    }

    // -------------------------------------------------------- listener bean

    @Test
    void rollerSessionListenerRegistersTheListenerType() {
        ServletListenerRegistrationBean<RollerSession> bean = config.rollerSessionListener();
        assertInstanceOf(RollerSession.class, bean.getListener());
    }

    // --------------------------------------------------- DispatcherServlet

    @Test
    void dispatcherServletIsMappedToRolSuffixWithLoadOnStartupOne() throws Exception {
        DispatcherServlet dispatcherServlet = new DispatcherServlet();

        DispatcherServletRegistrationBean bean =
                config.dispatcherServletRegistration(dispatcherServlet, noopMultipartProvider());

        assertSame(dispatcherServlet, bean.getServlet());
        assertEquals("*.rol", bean.getPath());
        assertEquals(1, loadOnStartupOf(bean));
    }

    @Test
    void dispatcherServletAppliesAnAvailableMultipartConfig() throws Exception {
        MultipartConfigElement multipartConfig = new MultipartConfigElement("");

        DispatcherServletRegistrationBean bean =
                config.dispatcherServletRegistration(new DispatcherServlet(), availableMultipartProvider(multipartConfig));

        assertSame(multipartConfig, bean.getMultipartConfig());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MultipartConfigElement> noopMultipartProvider() {
        ObjectProvider<MultipartConfigElement> provider = mock(ObjectProvider.class);
        // ifAvailable's default mocked behaviour (no-op) is exactly "nothing
        // available" -- exercising the call is enough to cover the line, no
        // stubbing needed.
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MultipartConfigElement> availableMultipartProvider(
            MultipartConfigElement element) {
        ObjectProvider<MultipartConfigElement> provider = mock(ObjectProvider.class);
        doAnswer(invocation -> {
            Consumer<MultipartConfigElement> consumer = invocation.getArgument(0);
            consumer.accept(element);
            return null;
        }).when(provider).ifAvailable(any());
        return provider;
    }

    // --------------------------------------------------------------- filters

    @Test
    void charEncodingFilterRunsFirstOnEveryRequestAndForward() {
        FilterRegistrationBean<CharEncodingFilter> bean = config.charEncodingFilterRegistration();
        assertInstanceOf(CharEncodingFilter.class, bean.getFilter());
        assertEquals(10, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD),
                bean.determineDispatcherTypes());
    }

    @Test
    void ipBanFilterGuardsOnlyTheCommentPathOnForward() {
        FilterRegistrationBean<IPBanFilter> bean = config.ipBanFilterRegistration();
        assertInstanceOf(IPBanFilter.class, bean.getFilter());
        assertEquals(20, bean.getOrder());
        assertEquals(Set.of("/roller-ui/rendering/comment/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.FORWARD), bean.determineDispatcherTypes());
    }

    @Test
    void springFirewallExceptionFilterRunsBeforeSecurityAtOrderThirty() {
        FilterRegistrationBean<SpringFirewallExceptionFilter> bean =
                config.springFirewallExceptionFilterRegistration();
        assertInstanceOf(SpringFirewallExceptionFilter.class, bean.getFilter());
        assertEquals(30, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD),
                bean.determineDispatcherTypes());
    }

    @Test
    void bootstrapFilterRunsAfterSecurityAtOrderFifty() {
        FilterRegistrationBean<BootstrapFilter> bean = config.bootstrapFilterRegistration();
        assertInstanceOf(BootstrapFilter.class, bean.getFilter());
        assertEquals(50, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST), bean.determineDispatcherTypes());
    }

    @Test
    void persistenceSessionFilterRunsAtOrderSixty() {
        FilterRegistrationBean<PersistenceSessionFilter> bean = config.persistenceSessionFilterRegistration();
        assertInstanceOf(PersistenceSessionFilter.class, bean.getFilter());
        assertEquals(60, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST), bean.determineDispatcherTypes());
    }

    @Test
    void initFilterRunsAtOrderSeventy() {
        FilterRegistrationBean<InitFilter> bean = config.initFilterRegistration();
        assertInstanceOf(InitFilter.class, bean.getFilter());
        assertEquals(70, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST), bean.determineDispatcherTypes());
    }

    @Test
    void requestMappingFilterRunsLastAtOrderEighty() {
        FilterRegistrationBean<RequestMappingFilter> bean = config.requestMappingFilterRegistration();
        assertInstanceOf(RequestMappingFilter.class, bean.getFilter());
        assertEquals(80, bean.getOrder());
        assertEquals(Set.of("/*"), Set.copyOf(bean.getUrlPatterns()));
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST), bean.determineDispatcherTypes());
    }
}

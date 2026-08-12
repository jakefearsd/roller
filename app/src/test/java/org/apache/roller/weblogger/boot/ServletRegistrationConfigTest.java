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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;

import org.apache.roller.weblogger.ui.controllers.ajax.ThemeDataServlet;
import org.apache.roller.weblogger.ui.controllers.ajax.UserDataServlet;
import org.apache.roller.weblogger.ui.core.RollerSession;
import org.apache.roller.weblogger.ui.core.filters.BootstrapFilter;
import org.apache.roller.weblogger.ui.core.filters.CharEncodingFilter;
import org.apache.roller.weblogger.ui.core.filters.InitFilter;
import org.apache.roller.weblogger.ui.core.filters.PersistenceSessionFilter;
import org.apache.roller.weblogger.ui.core.filters.SpringFirewallExceptionFilter;
import org.apache.roller.weblogger.ui.rendering.filters.RequestMappingFilter;
import org.apache.roller.weblogger.ui.rendering.servlets.FeedServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.MediaResourceServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.PageServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.PreviewResourceServlet;
import org.apache.roller.weblogger.ui.rendering.servlets.PreviewServlet;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        assertServlet(config.previewServletRegistration(), PreviewServlet.class,
                "/roller-ui/authoring/preview/*", 9);
        assertServlet(config.previewResourceServletRegistration(), PreviewResourceServlet.class,
                "/roller-ui/authoring/previewresource/*", 9);
    }

    @Test
    void ajaxServletsCarryTheirWebXmlPatternAndTheDefaultLoadOrder() throws Exception {
        // No <load-on-startup> element in web.xml for these two -- left at
        // ServletRegistrationBean's own constructor default, -1 (verified via
        // javap against spring-boot-4.1.0.jar: the no-arg and (T, String...)
        // constructors both set loadOnStartup = -1).
        assertServlet(config.userDataServletRegistration(), UserDataServlet.class,
                "/roller-ui/authoring/userdata/*", -1);
        assertServlet(config.themeDataServletRegistration(), ThemeDataServlet.class,
                "/roller-ui/authoring/themedata/*", -1);
    }

    /**
     * Enumerates every url-pattern this class registers a servlet under.
     * Used to pin the absence of the comment endpoints below rather than
     * asserting a specific bean method no longer exists -- the point is that
     * nothing reachable answers to a "comment"-shaped path, not the mechanics
     * of how that got removed.
     */
    private List<String> registeredPatterns() throws ReflectiveOperationException {
        List<String> patterns = new ArrayList<>();
        patterns.addAll(config.pageServletRegistration().getUrlMappings());
        patterns.addAll(config.feedServletRegistration().getUrlMappings());
        patterns.addAll(config.resourceServletRegistration().getUrlMappings());
        patterns.addAll(config.mediaResourceServletRegistration().getUrlMappings());
        patterns.addAll(config.searchServletRegistration().getUrlMappings());
        patterns.addAll(config.previewServletRegistration().getUrlMappings());
        patterns.addAll(config.previewResourceServletRegistration().getUrlMappings());
        patterns.addAll(config.userDataServletRegistration().getUrlMappings());
        patterns.addAll(config.themeDataServletRegistration().getUrlMappings());
        return patterns;
    }

    /**
     * The comment servlet, the comment authenticator servlet and the comment
     * ajax servlet's registration are all gone with the comment subsystem --
     * no url-pattern this class registers should mention "comment" in either
     * case.
     */
    @Test
    void noCommentServletsAreRegistered() throws Exception {
        assertTrue(registeredPatterns().stream()
                        .noneMatch(p -> p.contains("comment") || p.contains("Comment")),
                "comment servlets must be unregistered: " + registeredPatterns());
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

    /**
     * The SEO endpoints (SeoController) ride on extra container mappings
     * that DispatcherServletRegistrationBean's throwing
     * {@code addUrlMappings()} forces through the {@code configure()} hook,
     * so they are only observable at registration time -- hence the mocked
     * ServletContext.
     */
    @Test
    void dispatcherServletAlsoClaimsTheSeoUrlPatterns() throws Exception {
        DispatcherServletRegistrationBean bean =
                config.dispatcherServletRegistration(new DispatcherServlet(), noopMultipartProvider());

        ServletContext servletContext = mock(ServletContext.class);
        ServletRegistration.Dynamic registration = mock(ServletRegistration.Dynamic.class);
        when(servletContext.addServlet(anyString(), any(DispatcherServlet.class)))
                .thenReturn(registration);

        bean.onStartup(servletContext);

        verify(registration).addMapping("*.rol");
        verify(registration).addMapping(ServletRegistrationConfig.SEO_URL_PATTERNS);
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

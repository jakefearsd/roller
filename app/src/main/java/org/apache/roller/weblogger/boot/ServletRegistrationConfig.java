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

import java.util.EnumSet;
import java.util.List;

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Java-config transcription of {@code web.xml}'s {@code <servlet>}/
 * {@code <filter>}/{@code <servlet-mapping>}/{@code <filter-mapping>}
 * elements, none of which Boot's embedded container reads from
 * {@code web.xml} itself. One {@link ServletRegistrationBean} per servlet
 * row and one {@link FilterRegistrationBean} per filter row, transcribed
 * exactly from the normative tables in the Stage 1B Task 3 brief (in turn
 * transcribed from {@code web.xml}'s own load-bearing ordering comments —
 * see the filter registrations below for why the order matters).
 *
 * <p>{@code DebugFilter} is intentionally not registered here: it had no
 * {@code <filter-mapping>} in {@code web.xml} either (dev-only, wired up
 * manually when needed).
 */
@Configuration
public class ServletRegistrationConfig {

    // ------------------------------------------------------------------
    // Rendering / AJAX servlets (org.apache.roller.weblogger.ui.rendering.servlets
    // unless noted). loadOnStartup values transcribed verbatim from web.xml;
    // the three ajax servlets had no <load-on-startup> element, so they keep
    // the servlet-container default (0, i.e. "no eager init requested"),
    // matching setLoadOnStartup's default of not being called at all --
    // ServletRegistrationBean defaults loadOnStartup to -1 (lazy) when never
    // set, which is the closest equivalent to "element absent" and is left
    // alone here rather than guessing a value web.xml never specified.
    // ------------------------------------------------------------------

    @Bean
    public ServletRegistrationBean<PageServlet> pageServletRegistration() {
        ServletRegistrationBean<PageServlet> registration =
                new ServletRegistrationBean<>(new PageServlet(), "/roller-ui/rendering/page/*");
        registration.setLoadOnStartup(5);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<FeedServlet> feedServletRegistration() {
        ServletRegistrationBean<FeedServlet> registration =
                new ServletRegistrationBean<>(new FeedServlet(), "/roller-ui/rendering/feed/*");
        registration.setLoadOnStartup(5);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<ResourceServlet> resourceServletRegistration() {
        ServletRegistrationBean<ResourceServlet> registration =
                new ServletRegistrationBean<>(new ResourceServlet(), "/roller-ui/rendering/resources/*");
        registration.setLoadOnStartup(5);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<MediaResourceServlet> mediaResourceServletRegistration() {
        ServletRegistrationBean<MediaResourceServlet> registration =
                new ServletRegistrationBean<>(new MediaResourceServlet(), "/roller-ui/rendering/media-resources/*");
        registration.setLoadOnStartup(5);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<SearchServlet> searchServletRegistration() {
        ServletRegistrationBean<SearchServlet> registration =
                new ServletRegistrationBean<>(new SearchServlet(), "/roller-ui/rendering/search/*");
        registration.setLoadOnStartup(5);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<CommentServlet> commentServletRegistration() {
        ServletRegistrationBean<CommentServlet> registration =
                new ServletRegistrationBean<>(new CommentServlet(), "/roller-ui/rendering/comment/*");
        registration.setLoadOnStartup(7);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<RSDServlet> rsdServletRegistration() {
        ServletRegistrationBean<RSDServlet> registration =
                new ServletRegistrationBean<>(new RSDServlet(), "/roller-ui/rendering/rsd/*");
        registration.setLoadOnStartup(7);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<CommentAuthenticatorServlet> commentAuthenticatorServletRegistration() {
        ServletRegistrationBean<CommentAuthenticatorServlet> registration =
                new ServletRegistrationBean<>(new CommentAuthenticatorServlet(), "/CommentAuthenticatorServlet");
        registration.setLoadOnStartup(7);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<PreviewServlet> previewServletRegistration() {
        ServletRegistrationBean<PreviewServlet> registration =
                new ServletRegistrationBean<>(new PreviewServlet(), "/roller-ui/authoring/preview/*");
        registration.setLoadOnStartup(9);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<PreviewResourceServlet> previewResourceServletRegistration() {
        ServletRegistrationBean<PreviewResourceServlet> registration =
                new ServletRegistrationBean<>(new PreviewResourceServlet(), "/roller-ui/authoring/previewresource/*");
        registration.setLoadOnStartup(9);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<CommentDataServlet> commentDataServletRegistration() {
        return new ServletRegistrationBean<>(new CommentDataServlet(), "/roller-ui/authoring/commentdata/*");
    }

    @Bean
    public ServletRegistrationBean<UserDataServlet> userDataServletRegistration() {
        return new ServletRegistrationBean<>(new UserDataServlet(), "/roller-ui/authoring/userdata/*");
    }

    @Bean
    public ServletRegistrationBean<ThemeDataServlet> themeDataServletRegistration() {
        return new ServletRegistrationBean<>(new ThemeDataServlet(), "/roller-ui/authoring/themedata/*");
    }

    // ------------------------------------------------------------------
    // DispatcherServlet -- mapped to *.rol, exactly as web.xml's springMvc
    // <servlet-mapping> did. Naming this bean method (and therefore the
    // bean) "dispatcherServletRegistration" is load-bearing: Boot's own
    // DispatcherServletAutoConfiguration.DispatcherServletRegistrationCondition
    // backs off its default "/"-mapped DispatcherServletRegistrationBean
    // specifically when a bean named "dispatcherServletRegistration"
    // already exists (verified via javap against
    // spring-boot-webmvc-4.1.0.jar), so a differently-named bean here would
    // leave BOTH registrations active.
    // ------------------------------------------------------------------

    @Bean
    public DispatcherServletRegistrationBean dispatcherServletRegistration(
            DispatcherServlet dispatcherServlet,
            ObjectProvider<MultipartConfigElement> multipartConfig) {
        DispatcherServletRegistrationBean registration =
                new DispatcherServletRegistrationBean(dispatcherServlet, "*.rol");
        registration.setName("springMvc");
        registration.setLoadOnStartup(1);
        multipartConfig.ifAvailable(registration::setMultipartConfig);
        return registration;
    }

    // ------------------------------------------------------------------
    // Filters. Order and dispatcher types are the contract -- transcribed
    // verbatim from web.xml's <filter-mapping> order (which IS significant,
    // per web.xml's own "order IS important here" comment) into explicit
    // setOrder() values 10-80. Two ordering comments from web.xml carry
    // forward here verbatim because they explain *why* the order matters,
    // not just what it is:
    //   - CharEncodingFilter must run first: anything ahead of it must not
    //     cause request parsing, since that would lock in the wrong
    //     encoding.
    //   - SpringFirewallExceptionFilter must sit immediately before Spring
    //     Security (order 30, security at 40): it converts a
    //     RequestRejectedException thrown by Spring Security's firewall
    //     into a plain 404 instead of letting the exception propagate.
    // Order 40 (springSecurityFilterChain) is Boot-managed, not registered
    // here -- see application.properties' spring.security.filter.order /
    // spring.security.filter.dispatcher-types.
    // ------------------------------------------------------------------

    @Bean
    public FilterRegistrationBean<CharEncodingFilter> charEncodingFilterRegistration() {
        FilterRegistrationBean<CharEncodingFilter> registration = new FilterRegistrationBean<>(new CharEncodingFilter());
        registration.setOrder(10);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD));
        return registration;
    }

    @Bean
    public FilterRegistrationBean<IPBanFilter> ipBanFilterRegistration() {
        FilterRegistrationBean<IPBanFilter> registration = new FilterRegistrationBean<>(new IPBanFilter());
        registration.setOrder(20);
        registration.setUrlPatterns(List.of("/roller-ui/rendering/comment/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.FORWARD));
        return registration;
    }

    @Bean
    public FilterRegistrationBean<SpringFirewallExceptionFilter> springFirewallExceptionFilterRegistration() {
        FilterRegistrationBean<SpringFirewallExceptionFilter> registration =
                new FilterRegistrationBean<>(new SpringFirewallExceptionFilter());
        registration.setOrder(30);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD));
        return registration;
    }

    // Order 40 (springSecurityFilterChain) is registered automatically by
    // Boot's SecurityFilterAutoConfiguration -- see application.properties.

    @Bean
    public FilterRegistrationBean<BootstrapFilter> bootstrapFilterRegistration() {
        FilterRegistrationBean<BootstrapFilter> registration = new FilterRegistrationBean<>(new BootstrapFilter());
        registration.setOrder(50);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }

    @Bean
    public FilterRegistrationBean<PersistenceSessionFilter> persistenceSessionFilterRegistration() {
        FilterRegistrationBean<PersistenceSessionFilter> registration =
                new FilterRegistrationBean<>(new PersistenceSessionFilter());
        registration.setOrder(60);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }

    @Bean
    public FilterRegistrationBean<InitFilter> initFilterRegistration() {
        FilterRegistrationBean<InitFilter> registration = new FilterRegistrationBean<>(new InitFilter());
        registration.setOrder(70);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestMappingFilter> requestMappingFilterRegistration() {
        FilterRegistrationBean<RequestMappingFilter> registration =
                new FilterRegistrationBean<>(new RequestMappingFilter());
        registration.setOrder(80);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }

    /**
     * Moved from {@code RollerApplication}'s temporary Task-2 placeholder
     * (formerly a {@code <listener>} in web.xml) now that this is the
     * permanent home for web.xml-derived registrations.
     */
    @Bean
    public ServletListenerRegistrationBean<RollerSession> rollerSessionListener() {
        return new ServletListenerRegistrationBean<>(new RollerSession());
    }
}

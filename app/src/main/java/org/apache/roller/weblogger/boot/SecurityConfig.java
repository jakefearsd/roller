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

import java.util.ArrayList;
import java.util.List;

import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.ui.core.security.RollerRememberMeAuthenticationProvider;
import org.apache.roller.weblogger.ui.core.security.RollerRememberMeServices;
import org.apache.roller.weblogger.ui.core.security.RollerUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Java-config replacement for {@code security.xml} (deleted by this class,
 * along with {@code RollerApplication}'s {@code @ImportResource} that
 * temporarily loaded it as Stage 1B Task 3 scaffolding).
 *
 * <p>{@code security.xml} could not simply move unchanged (see the Task 3
 * report's Deviation 1): Spring Security 7.1 dropped the
 * {@code AccessDecisionManager}/{@code SecurityMetadataSource} path
 * entirely, forcing that file onto the {@code AuthorizationManager} path
 * (bare {@code hasRole()}/{@code hasAnyRole()} SpEL plus a
 * {@code GrantedAuthorityDefaults(rolePrefix = "")} bean) as an interim fix.
 * This class reproduces those semantics directly in Java, using
 * {@code hasAuthority}/{@code hasAnyAuthority} against Roller's verbatim
 * {@code "admin"}/{@code "editor"} authority strings instead -- no
 * {@code GrantedAuthorityDefaults} bean is needed at all, since
 * {@code hasAuthority}/{@code hasAnyAuthority} never apply Spring Security's
 * default {@code ROLE_} prefix in the first place (only {@code hasRole}/
 * {@code hasAnyRole} do).
 *
 * <p>Two authorization surfaces, matching the XML's two {@code <http>}
 * elements: a {@link WebSecurityCustomizer} that reproduces
 * {@code security="none"} for the three static-asset patterns (the XML's own
 * {@code use-authorization-manager} comment explains why {@code ignoring()}
 * is the right replacement, not a {@code permitAll()} rule -- it skips the
 * whole filter chain rather than just the authorization check), and the one
 * real {@link SecurityFilterChain} below for everything else.
 */
@Configuration
public class SecurityConfig {

    /**
     * Publishes the same {@link PasswordEncoder} {@link RollerContext}
     * always exposed via {@code getPasswordEncoder()}, but built here instead
     * of from {@code RollerContext.initializeSecurityFeatures} (deleted):
     * {@link #authenticationManager} needs it during context refresh, well
     * before {@code RollerLifecycle.start()} -- a {@code SmartLifecycle} bean
     * that only starts once refresh has completed -- would otherwise have
     * built it. {@link RollerContext#setPasswordEncoder} keeps the static
     * accessor {@code User} reads from working exactly as before.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        PasswordEncoder encoder = RollerContext.createPasswordEncoder();
        RollerContext.setPasswordEncoder(encoder);
        return encoder;
    }

    @Bean
    public RollerUserDetailsService rollerUserDetailsService() {
        return new RollerUserDetailsService();
    }

    @Bean
    public RollerRememberMeServices rollerRememberMeServices(RollerUserDetailsService userDetailsService) {
        return new RollerRememberMeServices(userDetailsService);
    }

    /**
     * {@code security.xml}'s {@code <authentication-manager>}: a
     * {@link ProviderManager} of [conditional remember-me provider, DAO
     * provider], in that order. The remember-me provider is registered only
     * when {@code rememberme.enabled=true} -- {@code security.xml} always
     * declared {@code rememberMeAuthenticationProvider}, and
     * {@code RollerContext.initializeSecurityFeatures} (deleted) removed it
     * from the live provider list at startup when the property was off; this
     * replaces that runtime mutation with an upfront conditional, since the
     * provider is now built here rather than looked up by name afterward.
     * ({@link RollerRememberMeAuthenticationProvider}'s own constructor
     * refuses to build at all if {@code rememberme.enabled=true} but the key
     * is still the {@code springRocks} default, so guarding construction on
     * the same property is safe -- it is never constructed with a bad key.)
     *
     * <p>{@link DaoAuthenticationProvider} has exactly one constructor
     * (verified via {@code javap} against {@code spring-security-core-7.1.0.jar}:
     * {@code DaoAuthenticationProvider(UserDetailsService)}) -- {@code
     * setPasswordEncoder(PasswordEncoder)} is, contrary to this task's
     * planning assumption, still present in 7.1.0, not removed. The encoder
     * is still applied in one atomic step immediately after construction,
     * inside this {@code @Bean} method, rather than via the old pattern of a
     * separate by-name bean lookup mutating an already-published provider
     * later at startup.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            RollerUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {

        List<AuthenticationProvider> providers = new ArrayList<>();
        if (WebloggerConfig.getBooleanProperty("rememberme.enabled")) {
            providers.add(new RollerRememberMeAuthenticationProvider());
        }

        DaoAuthenticationProvider daoProvider = new DaoAuthenticationProvider(userDetailsService);
        daoProvider.setPasswordEncoder(passwordEncoder);
        providers.add(daoProvider);

        return new ProviderManager(providers);
    }

    /**
     * Reproduces {@code security.xml}'s
     * {@code <http pattern="/images/**" security="none"/>} (and the matching
     * {@code /scripts/**}, {@code /styles/**} elements): these three patterns
     * skip the security filter chain entirely rather than merely being
     * granted {@code permitAll()} inside it -- the closest available
     * replacement for {@code security="none"} now that the XML namespace
     * parser is gone.
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers("/images/**", "/scripts/**", "/styles/**");
    }

    /**
     * Overrides Boot's auto-configured {@code pathPatternRequestMatcherBuilder}
     * bean (Boot's own is {@code @ConditionalOnMissingBean}, confirmed via
     * {@code javap} against {@code spring-boot-security-4.1.0.jar}'s
     * {@code ServletWebSecurityAutoConfiguration$PathPatternRequestMatcherBuilderConfiguration}),
     * which derives its {@code basePath} from
     * {@code DispatcherServletPath.getPath()} -- unconditionally, except for
     * the literal value {@code "/"} (also confirmed via {@code javap}: its
     * bytecode is a single {@code if (!path.equals("/")) builder.basePath(path)}).
     * {@link ServletRegistrationConfig#dispatcherServletRegistration} maps
     * {@code DispatcherServlet} to {@code *.rol} (transcribing web.xml
     * verbatim, per Task 3), so that path is {@code "*.rol"}, not {@code "/"};
     * Boot's auto-configured builder then calls
     * {@code PathPatternRequestMatcher.Builder.basePath("*.rol")}, which
     * throws {@code IllegalArgumentException: basePath must start with '/'}
     * at context-refresh time -- confirmed live: {@code java -jar} failed to
     * start with exactly that exception until this bean was added. Every
     * {@code requestMatchers(String...)} call above and in
     * {@link #webSecurityCustomizer} routes through this builder (verified
     * via {@code javap} against {@code spring-security-config-7.1.0.jar}:
     * {@code AbstractRequestMatcherRegistry} resolves it from the
     * application context), so this is not an edge case -- it breaks the
     * entire authorization configuration on every real startup. The
     * patterns declared throughout this class are already the full request
     * path (e.g. {@code /roller-ui/menu*}), matching what
     * {@code *.rol}-mapped {@code DispatcherServlet} itself sees (a suffix
     * servlet mapping consumes no path prefix, unlike a {@code /prefix/*}
     * mapping), so the fix is simply to keep the builder's own default
     * (no {@code basePath} at all), not to reproduce Boot's broken
     * derivation.
     */
    @Bean
    public PathPatternRequestMatcher.Builder pathPatternRequestMatcherBuilder() {
        return PathPatternRequestMatcher.withDefaults();
    }

    /**
     * Reproduces {@code security.xml}'s single real {@code <http>} element.
     *
     * <p>Authorization patterns are translated from the XML's Ant-style
     * {@code **} suffixes to {@code PathPatternRequestMatcher} equivalents
     * (Ant matchers are removed in Spring Security 7; passing plain strings
     * to {@code requestMatchers(String...)} builds
     * {@code PathPatternRequestMatcher} instances under the hood --
     * confirmed via {@code javap} against {@code spring-security-config-7.1.0.jar}:
     * {@code AbstractRequestMatcherRegistry} holds a
     * {@code PathPatternRequestMatcher.Builder} field). Every pattern below
     * was checked against the controllers that actually declare the routes
     * it must cover (see the Task 4 report's semantics table):
     * <ul>
     *   <li>{@code /roller-ui/login-redirect**}, {@code profile**},
     *       {@code createWeblog**}, {@code menu**}, {@code setup**} matched
     *       {@code X.rol} and {@code X!action.rol} in the XML -- both are a
     *       single path segment, so the PathPattern equivalent is a bare
     *       {@code *} suffix ({@code /roller-ui/profile*}, etc.), not
     *       {@code **} (which would also match extra path segments the old
     *       Ant pattern's single-segment glue never did).</li>
     *   <li>{@code /roller-ui/authoring/**} and {@code /roller-ui/admin/**}
     *       keep their {@code **} verbatim -- both are true multi-segment
     *       subtrees (authoring covers e.g. {@code authoring/overlay/*} and
     *       {@code authoring/preview/*} too), which {@code **} still means
     *       under {@code PathPatternRequestMatcher}.</li>
     *   <li>{@code /rewrite-status*} already used a single {@code *} in the
     *       XML and is carried over unchanged.</li>
     * </ul>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, AuthenticationManager authenticationManager,
            RollerRememberMeServices rememberMeServices) throws Exception {

        http
            .authenticationManager(authenticationManager)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/roller-ui/login-redirect*",
                        "/roller-ui/profile*",
                        "/roller-ui/createWeblog*",
                        "/roller-ui/menu*",
                        "/roller-ui/authoring/**")
                    .hasAnyAuthority("admin", "editor")
                .requestMatchers(
                        "/roller-ui/admin/**",
                        "/roller-ui/setup*",
                        "/rewrite-status*")
                    .hasAuthority("admin")
                // Catch-all, must be last (first-match-wins): the public
                // blog and every other admin-UI page are anonymous by
                // default. See security.xml's own comment on this same rule
                // for why an implicit deny-by-default here would be wrong.
                .anyRequest().permitAll())
            .formLogin(form -> form
                .loginPage("/roller-ui/login.rol")
                .loginProcessingUrl("/roller_j_security_check")
                .usernameParameter("j_username")
                .passwordParameter("j_password")
                // Where a successful login lands when there is no saved
                // request to return to. Without this Spring Security falls
                // back to "/", which forwards to the admin-only setup page
                // when no frontpage weblog is configured -- so every non-admin
                // user was sent to a 403 the moment they signed in
                // successfully. login-redirect.jsp already exists for exactly
                // this job: straight to the editor when the user has one
                // weblog, otherwise the menu.
                .defaultSuccessUrl("/roller-ui/login-redirect.rol")
                .failureUrl("/roller-ui/login.rol?error=true"))
            // security.xml registered no <logout> element at all -- logout is
            // handled entirely at the application level (/logout.rol forwards
            // to logout-redirect.jsp, which calls session.invalidate()).
            // Boot's default LogoutFilter must stay off, or a bare /logout
            // would appear and behave differently from the app's own.
            .logout(AbstractHttpConfigurer::disable)
            .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
            // Roller's media-file editor uses iframes.
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        return http.build();
    }
}

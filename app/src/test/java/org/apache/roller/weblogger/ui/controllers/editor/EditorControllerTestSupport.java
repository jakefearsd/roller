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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared fixture for the editor controller tests.
 *
 * <p>Gives every subclass a mocked business tier ({@link MockWeblogger}), a
 * mocked request already carrying the {@code authenticatedUser} and
 * {@code actionWeblog} attributes the {@code RollerHandlerInterceptor} would
 * normally have set, and a real {@link ExtendedModelMap} /
 * {@link RedirectAttributesModelMap} so that the message and flash plumbing in
 * {@code BaseController} runs for real instead of against a mock.
 *
 * <p>Messages are resolved through a {@link StaticMessageSource} with nothing
 * registered, so {@code getText("some.key")} returns {@code "some.key"}. Tests
 * therefore assert on message <em>keys</em>, which is the decision that
 * matters — a controller picking the wrong key is a real defect, whereas the
 * English wording is not this layer's business. Where the message
 * <em>argument</em> matters (a category name, a count) the test registers a
 * pattern with {@link #registerMessage} and asserts on the formatted result.
 */
abstract class EditorControllerTestSupport {

    protected static final String WEBLOG_HANDLE = "testblog";
    protected static final String USER_NAME = "testuser";

    protected MockWeblogger weblogger;
    protected HttpServletRequest request;
    protected Weblog weblog;
    protected User user;
    protected StaticMessageSource messageSource;

    @BeforeEach
    void installMockBusinessTier() {
        weblogger = MockWeblogger.install();
        messageSource = new StaticMessageSource();
        // Without this, a key that has no registered message and *does* carry
        // arguments comes back unformatted, and assertions on the argument
        // would silently pass against the bare key.
        messageSource.setAlwaysUseMessageFormat(false);

        user = new User();
        user.setId("user-1");
        user.setUserName(USER_NAME);
        user.setEmailAddress("testuser@example.com");
        user.setDateCreated(new Date());
        user.setEnabled(Boolean.TRUE);

        weblog = new Weblog();
        weblog.setId("weblog-1");
        weblog.setHandle(WEBLOG_HANDLE);
        weblog.setName("Test Blog");
        weblog.setLocale("en_US");
        weblog.setTimeZone("America/New_York");

        request = mock(HttpServletRequest.class);
        when(request.getLocale()).thenReturn(Locale.US);
        when(request.getAttribute("authenticatedUser")).thenReturn(user);
        when(request.getAttribute("actionWeblog")).thenReturn(weblog);
    }

    @AfterEach
    void removeMockBusinessTier() {
        // The factory is static and shared with every other test class in the
        // JVM. Leaving a mock behind would replace the real business tier for
        // whatever runs next.
        MockWeblogger.uninstall();
    }

    /**
     * Finish wiring a controller the way Spring would: inject the message
     * source that {@code BaseController} declares {@code @Autowired}.
     */
    protected <T extends BaseController> T prepare(T controller) {
        setField(controller, "messageSource", messageSource);
        return controller;
    }

    /**
     * Register a real message pattern, for the cases where a test needs to
     * prove that the right <em>argument</em> was passed and not just the right
     * key.
     */
    protected void registerMessage(String key, String pattern) {
        messageSource.addMessage(key, Locale.US, pattern);
    }

    protected Model newModel() {
        return new ExtendedModelMap();
    }

    protected RedirectAttributes newRedirectAttributes() {
        return new RedirectAttributesModelMap();
    }

    @SuppressWarnings("unchecked")
    protected static List<String> errors(Model model) {
        List<String> errors = (List<String>) model.getAttribute("errors");
        return errors == null ? Collections.emptyList() : errors;
    }

    @SuppressWarnings("unchecked")
    protected static List<String> messages(Model model) {
        List<String> messages = (List<String>) model.getAttribute("messages");
        return messages == null ? Collections.emptyList() : messages;
    }

    @SuppressWarnings("unchecked")
    protected static List<String> flashErrors(RedirectAttributes redirectAttributes) {
        List<String> errors = (List<String>) redirectAttributes.getFlashAttributes().get("errors");
        return errors == null ? Collections.emptyList() : errors;
    }

    @SuppressWarnings("unchecked")
    protected static List<String> flashMessages(RedirectAttributes redirectAttributes) {
        List<String> messages = (List<String>) redirectAttributes.getFlashAttributes().get("messages");
        return messages == null ? Collections.emptyList() : messages;
    }

    /**
     * Stub a runtime (database-backed) configuration property.
     *
     * <p>{@code WebloggerRuntimeConfig} reads these through
     * {@code PropertiesManager}, which is one of the mocked managers, so an
     * unstubbed property reads as absent — and {@code getBooleanProperty} then
     * reports {@code false}. Tests that exercise a feature gated on runtime
     * config have to say so explicitly.
     */
    protected void givenRuntimeProperty(String name, String value) {
        try {
            when(weblogger.getPropertiesManager().getProperty(name))
                    .thenReturn(new RuntimeConfigProperty(name, value));
        } catch (WebloggerException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Override a static {@code WebloggerConfig} property for the duration of a
     * test, returning the previous value so the caller can put it back.
     *
     * <p>{@code WebloggerConfig} exposes no general setter — it is loaded once
     * per JVM from the classpath — but the backing {@code Properties} object is
     * mutable, so this reaches it directly. Callers <em>must</em> restore the
     * previous value in an {@code @AfterEach}: the config is process-global.
     */
    protected static String overrideConfigProperty(String key, String value) {
        Properties config = webloggerConfigProperties();
        String previous = config.getProperty(key);
        if (value == null) {
            config.remove(key);
        } else {
            config.setProperty(key, value);
        }
        return previous;
    }

    private static Properties webloggerConfigProperties() {
        try {
            Field field = WebloggerConfig.class.getDeclaredField("config");
            field.setAccessible(true);
            return (Properties) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Could not reach WebloggerConfig's backing Properties. Tests that need to "
                            + "toggle a static config property depend on it; if the field was "
                            + "renamed, update webloggerConfigProperties().", e);
        }
    }

    /**
     * Set an inaccessible field. Needed because {@code BaseController}'s
     * {@code messageSource} is {@code protected} and these tests live in a
     * different package, and because spring-test (and its
     * {@code ReflectionTestUtils}) is not on this project's test classpath.
     */
    protected static void setField(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Could not set field " + name, e);
            }
        }
        throw new IllegalStateException("No field named '" + name + "' on "
                + target.getClass().getName() + " or its supertypes. If BaseController's "
                + "message source was renamed, update EditorControllerTestSupport.prepare().");
    }
}

package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Map;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.startup.MockMailProvider;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.auth.AdminScoped;
import org.apache.roller.weblogger.ui.restapi.dto.AdminDtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Only runtime-scoped properties are settable. The boot-scoped ones are boot
 * -scoped deliberately: promoting them would put "stop hashing passwords" and
 * "disable HTML sanitization" on an HTTP endpoint.
 */
class AdminApiTest {

    @Test
    void securityInvariantsAreNotSettable() {
        for (String name : new String[] {
                "weblogAdminsUntrusted", "passwds.encryption.enabled",
                "rememberme.enabled", "themes.reload.mode",
                "users.firstUserAdmin", "search.enabled" }) {
            assertThrows(ApiException.class,
                    () -> AdminDtos.requireRuntimeProperty(name),
                    name + " must not be settable through the API");
        }
    }

    @Test
    void aKnownRuntimePropertyIsAccepted() {
        assertDoesNotThrow(() -> AdminDtos.requireRuntimeProperty("groupblogging.enabled"));
        assertDoesNotThrow(() -> AdminDtos.requireRuntimeProperty("entry.trash.retention.days"));
    }

    @Test
    void anUnknownPropertyIsRejected() {
        assertThrows(ApiException.class,
                () -> AdminDtos.requireRuntimeProperty("no.such.property"));
    }

    // -----------------------------------------------------------------
    // Controller-level tests: routing, status codes, and the guards that
    // stand between raw client input and a manager call -- these run
    // against a Mockito-mocked Weblogger, matching CategoriesApiTest's
    // standalone style, EXCEPT the two tests that depend on
    // PasswordLinkMailer.isReady() (site.adminemail + a real mail
    // transport), which need the real bootstrapped Weblogger to control
    // deterministically and are isolated below.
    // -----------------------------------------------------------------

    private MockMvc mockMvc(AdminApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        UserManager userManager = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(userManager);
        return weblogger;
    }

    private AdminApi controllerFor(Weblogger weblogger) {
        AdminApi controller = new AdminApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static User aUser(String userName) {
        User user = new User();
        user.setUserName(userName);
        user.setScreenName("Screen " + userName);
        user.setEmailAddress(userName + "@example.test");
        user.setEnabled(Boolean.TRUE);
        return user;
    }

    @Test
    void listUsersReturnsUserViewsWithRoles() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        User user = aUser("alice");
        when(weblogger.getUserManager().getUsers(null, null, null, 0, 50)).thenReturn(List.of(user));
        when(weblogger.getUserManager().getRoles(user)).thenReturn(List.of("editor", "admin"));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/admin/users"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.size());
        assertEquals("alice", json.get(0).get("userName").asString());
        assertTrue(json.get(0).get("enabled").asBoolean());
        assertEquals(2, json.get(0).get("globalRoles").size());
    }

    @Test
    void listUsersRejectsANegativeOffset() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/admin/users").param("offset", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getUserManager(), never()).getUsers(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void listUsersRejectsANonPositiveLimit() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/admin/users").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getUserManager(), never()).getUsers(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void postRejectsABlankUserName() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\" \",\"emailAddress\":\"a@example.test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getUserManager(), never()).addUser(any());
    }

    @Test
    void postRejectsADisallowedUserNameCharacter() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"al ice!\",\"emailAddress\":\"a@example.test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getUserManager(), never()).addUser(any());
    }

    @Test
    void postRejectsAMalformedEmailAddress() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"alice\",\"emailAddress\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getUserManager(), never()).addUser(any());
    }

    /**
     * Checked with enabled=null (any status), not UserManager.addUser's own
     * enabled-only duplicate check -- see AdminApi.createUser's comment.
     * Reached and refused before PasswordLinkMailer.isReady() is ever
     * consulted, which is what keeps this test independent of ambient mail
     * configuration.
     */
    @Test
    void postRejectsADuplicateUserName() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(aUser("alice"));

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"alice\",\"emailAddress\":\"a@example.test\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getUserManager(), never()).addUser(any());
    }

    /**
     * The load-bearing shape of the create-user body: it has no place to put
     * a password even if a caller tried, and {@code UserCreate} carries only
     * the three fields listed here.
     */
    @Test
    void userCreateHasNoPasswordField() {
        var components = AdminDtos.UserCreate.class.getRecordComponents();
        assertEquals(3, components.length);
        for (var component : components) {
            assertFalse(component.getName().toLowerCase().contains("password"),
                    "UserCreate must never carry a password field: " + component.getName());
        }
    }

    @Test
    void patchIsNotFoundForAnUnknownUserName() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/admin/users/{userName}", "nosuchuser")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getUserManager(), never()).saveUser(any());
    }

    @Test
    void patchUpdatesEnabledScreenNameAndEmail() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        User user = aUser("alice");
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(user);
        when(weblogger.getUserManager().getRoles(user)).thenReturn(List.of("editor"));

        String body = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/admin/users/{userName}", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"screenName\":\"New Name\","
                                + "\"emailAddress\":\"new@example.test\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verify(weblogger.getUserManager()).saveUser(user);
        assertFalse(user.getEnabled());
        assertEquals("New Name", user.getScreenName());
        assertEquals("new@example.test", user.getEmailAddress());
        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertFalse(json.get("enabled").asBoolean());
    }

    @Test
    void patchRejectsAMalformedEmailAddress() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        User user = aUser("alice");
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(user);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/admin/users/{userName}", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailAddress\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getUserManager(), never()).saveUser(any());
    }

    @Test
    void patchRejectsABlankScreenName() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        User user = aUser("alice");
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(user);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/admin/users/{userName}", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"screenName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getUserManager(), never()).saveUser(any());
    }

    // --- Runtime config ---

    private Weblogger mockedConfigWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        PropertiesManager propertiesManager = mock(PropertiesManager.class);
        when(weblogger.getPropertiesManager()).thenReturn(propertiesManager);
        return weblogger;
    }

    @Test
    void listConfigReturnsEveryDeclaredPropertyWithItsCurrentOrDefaultValue() throws Exception {
        Weblogger weblogger = mockedConfigWeblogger();
        when(weblogger.getPropertiesManager().getProperty(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(null);

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/admin/config"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        boolean found = false;
        for (int i = 0; i < json.size(); i++) {
            if ("groupblogging.enabled".equals(json.get(i).get("name").asString())) {
                found = true;
                assertEquals("boolean", json.get(i).get("type").asString());
            }
            // The security invariants must never appear in the settable list either.
            assertNotEquals("weblogAdminsUntrusted", json.get(i).get("name").asString());
        }
        assertTrue(found, "groupblogging.enabled must be in the runtime config list");
    }

    @Test
    void patchConfigRejectsAnUnknownProperty() throws Exception {
        Weblogger weblogger = mockedConfigWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"no.such.property\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getPropertiesManager(), never()).saveProperties(any());
    }

    @Test
    void patchConfigRejectsASecurityInvariant() throws Exception {
        Weblogger weblogger = mockedConfigWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weblogAdminsUntrusted\":\"false\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getPropertiesManager(), never()).saveProperties(any());
    }

    @Test
    void patchConfigRejectsAValueOfTheWrongType() throws Exception {
        Weblogger weblogger = mockedConfigWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupblogging.enabled\":\"not-a-boolean\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getPropertiesManager(), never()).saveProperties(any());
    }

    @Test
    void patchConfigRejectsAnEmptyBody() throws Exception {
        Weblogger weblogger = mockedConfigWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getPropertiesManager(), never()).saveProperties(any());
    }

    @Test
    void patchConfigSetsAKnownProperty() throws Exception {
        Weblogger weblogger = mockedConfigWeblogger();

        String body = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/admin/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupblogging.enabled\":\"true\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, RuntimeConfigProperty>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(weblogger.getPropertiesManager()).saveProperties(captor.capture());
        assertEquals("true", captor.getValue().get("groupblogging.enabled").getValue());

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.size());
        assertEquals("true", json.get(0).get("value").asString());
        assertEquals("boolean", json.get(0).get("type").asString());
    }

    // --- Permission/scope declarations ---

    @Test
    void declaresAdminAsTheRequiredGlobalPermissionAndNoWeblogRequirement() {
        assertEquals(List.of(GlobalPermission.ADMIN), new AdminApi().requiredGlobalPermissionActions());
        assertTrue(new AdminApi().isUserRequired());
        assertFalse(new AdminApi().isWeblogRequired());
    }

    /**
     * The one thing this task exists to guard: without {@code @AdminScoped}
     * on the class, {@code ApiScopeInterceptor} never applies the ADMIN-role
     * ceiling to these handlers and a lower-scoped token could run them.
     */
    @Test
    void carriesAdminScoped() {
        assertTrue(AdminApi.class.isAnnotationPresent(AdminScoped.class),
                "AdminApi must carry @AdminScoped or ApiScopeInterceptor never applies the ADMIN-role "
                        + "ceiling to it.");
    }

    // -----------------------------------------------------------------
    // Integration-style tests: the two behaviours that genuinely depend on
    // PasswordLinkMailer.isReady() (site.adminemail + a real mail
    // transport), which only the real, bootstrapped Weblogger can control
    // deterministically. Both explicitly set the ambient state they need
    // and restore it afterward, so this class does not become order
    // -dependent on whatever an earlier test class left behind (see
    // CLAUDE.md's CI Order Dependency note).
    // -----------------------------------------------------------------

    @BeforeAll
    static void bootstrap() throws Exception {
        TestUtils.setupWeblogger();
    }

    private String previousAdminEmail;
    private String createdUserName;
    private String baselineUserName;
    private MockMailProvider mail;
    private boolean passwordEncoderInstalled;
    private org.springframework.security.crypto.password.PasswordEncoder previousPasswordEncoder;

    @AfterEach
    void tearDownIntegration() throws Exception {
        if (createdUserName != null) {
            WebloggerFactory.getWeblogger().getUserTokenManager()
                    .removeTokens(WebloggerFactory.getWeblogger().getUserManager()
                            .getUserByUserName(createdUserName, null));
            TestUtils.teardownUser(createdUserName);
            createdUserName = null;
        }
        if (baselineUserName != null) {
            TestUtils.teardownUser(baselineUserName);
            baselineUserName = null;
        }
        if (previousAdminEmail != null) {
            setAdminEmailNow(previousAdminEmail);
            previousAdminEmail = null;
        }
        if (mail != null) {
            MockMailProvider.uninstall();
            mail = null;
        }
        if (passwordEncoderInstalled) {
            org.apache.roller.weblogger.ui.core.RollerContext.setPasswordEncoder(previousPasswordEncoder);
            passwordEncoderInstalled = false;
        }
        TestUtils.endSession(true);
    }

    /**
     * {@code User.resetPassword} (which {@code AdminApi.createUser} calls to
     * give the new disabled account a random, never-disclosed password)
     * reads {@code RollerContext.getPasswordEncoder()} -- normally published
     * by {@code SecurityConfig}'s bean method at Spring context refresh,
     * which never runs in this MockMvc-standalone test. Without installing
     * one here, {@code resetPassword} throws a bare NPE.
     */
    @SuppressWarnings("deprecation") // NoOpPasswordEncoder -- fine for a test double, never production
    private void installNoopPasswordEncoder() {
        previousPasswordEncoder = org.apache.roller.weblogger.ui.core.RollerContext.getPasswordEncoder();
        org.apache.roller.weblogger.ui.core.RollerContext.setPasswordEncoder(
                org.springframework.security.crypto.password.NoOpPasswordEncoder.getInstance());
        passwordEncoderInstalled = true;
    }

    private void setAdminEmail(String value) throws Exception {
        PropertiesManager pmgr = WebloggerFactory.getWeblogger().getPropertiesManager();
        RuntimeConfigProperty existing = pmgr.getProperty("site.adminemail");
        previousAdminEmail = existing == null ? "" : existing.getValue();
        setAdminEmailNow(value);
    }

    /**
     * roller_properties is pre-seeded at startup, so this is always an
     * update -- see AdminApi.patchConfig's comment on the same landmine:
     * PropertiesManager.saveProperty persists a not-yet-managed instance
     * outright rather than upserting, so handing it a bare {@code new
     * RuntimeConfigProperty(name, value)} for a name that already has a row
     * throws a raw duplicate-key exception.
     */
    private void setAdminEmailNow(String value) throws Exception {
        PropertiesManager pmgr = WebloggerFactory.getWeblogger().getPropertiesManager();
        RuntimeConfigProperty property = pmgr.getProperty("site.adminemail");
        if (property != null) {
            property.setValue(value);
        } else {
            property = new RuntimeConfigProperty("site.adminemail", value);
        }
        pmgr.saveProperty(property);
        WebloggerFactory.getWeblogger().flush();
    }

    /**
     * {@code PasswordLinkMailer.isReady()} requires a non-blank {@code
     * site.adminemail} in addition to a configured transport -- forcing it
     * blank is sufficient to force {@code isReady()} false regardless of
     * whatever mail provider state another test left behind.
     */
    @Test
    void postRefusesToCreateAnAccountWhenMailIsNotConfigured() throws Exception {
        setAdminEmail("");
        // Plain alphanumeric on purpose, NOT TestUtils.JUNIT_PREFIX ("junit_")
        // -- AdminApi.createUser's own char-allowlist check (mirroring
        // UserEditController's, "username.allowedChars" = "A-Za-z0-9" by
        // default) would refuse an underscore before this test ever reaches
        // the guard under test. TestUtils.setupUser bypasses that check
        // entirely by calling UserManager.addUser directly.
        String userName = "adminapiitnomail";

        AdminApi controller = new AdminApi();
        controller.weblogger = WebloggerFactory.getWeblogger();

        mockMvc(controller)
                .perform(post("/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"" + userName + "\","
                                + "\"emailAddress\":\"mailnotready@example.test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        assertNull(WebloggerFactory.getWeblogger().getUserManager().getUserByUserName(userName, null),
                "a refused create must leave no account behind");
    }

    /**
     * The end-to-end proof of the class javadoc's central claim: the account
     * lands DISABLED, and the only thing ever sent is a link, never a
     * plaintext password -- there is nowhere in the response or the email
     * for one to be.
     */
    @Test
    void postCreatesADisabledAccountAndEmailsASetPasswordLink() throws Exception {
        mail = MockMailProvider.install();
        setAdminEmail("admin@example.test");
        installNoopPasswordEncoder();
        // JPAUserManagerImpl.addUser force-enables (and admin-roles) the
        // FIRST user ever added when users.firstUserAdmin is set -- real
        // bootstrap behaviour this API can never actually hit in production
        // (only an already-enabled admin can reach an @AdminScoped
        // endpoint at all), but an isolated test database starts with no
        // enabled users unless one is seeded first.
        User baseline = TestUtils.setupUser("adminapiitbaseline");
        baselineUserName = baseline.getUserName();
        // Plain alphanumeric -- see postRefusesToCreateAnAccountWhenMailIsNotConfigured's comment.
        String userName = "adminapiitcreate";

        AdminApi controller = new AdminApi();
        controller.weblogger = WebloggerFactory.getWeblogger();

        String body = mockMvc(controller)
                .perform(post("/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"" + userName + "\","
                                + "\"emailAddress\":\"newadminuser@example.test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        createdUserName = userName;

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(userName, json.get("userName").asString());
        assertFalse(json.get("enabled").asBoolean(), "a newly created account must start disabled");
        assertFalse(body.toLowerCase().contains("password"),
                "no field of the response may even be named 'password'");

        User stored = WebloggerFactory.getWeblogger().getUserManager().getUserByUserName(userName, null);
        assertNotNull(stored);
        assertFalse(stored.getEnabled());

        MimeMessage sent = mail.onlyMessage();
        assertTrue(sent.getContent().toString().contains("resetPassword.rol?token="),
                "the email must carry a set-password link, never a password");
        assertFalse(sent.getContent().toString().toLowerCase().contains("password="),
                "the email must never carry a plaintext password parameter");
    }
}

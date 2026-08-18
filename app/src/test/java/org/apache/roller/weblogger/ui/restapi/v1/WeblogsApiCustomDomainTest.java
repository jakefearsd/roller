package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.dto.AdminDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code WeblogsApi}'s handling of {@code customDomain} on GET/PATCH
 * {@code /v1/weblogs/{handle}}. The point of this class is that the 400/409
 * outcomes come from {@link org.apache.roller.weblogger.ui.controllers.CustomDomainRules}
 * -- the same class {@code WeblogConfigController} calls for the JSP Weblog
 * Settings form -- not from a second, API-side reimplementation of the
 * hostname rule that could drift from it.
 *
 * <p>Calls {@code WeblogsApi}'s handler methods directly rather than through
 * MockMvc (contrast {@code WeblogsApiTest}): {@code ApiException} is
 * unchecked, so a direct call lets {@code assertThrows} capture it and read
 * {@code getStatus()} without a round trip through {@code
 * ApiExceptionHandler} and a serialized problem-detail body.
 */
class WeblogsApiCustomDomainTest {

    private WeblogsApi weblogsApi;
    private WeblogManager weblogManager;
    private Weblog vhostblog;
    private Weblog plainblog;

    @BeforeEach
    void setUp() throws WebloggerException {
        Weblogger weblogger = mock(Weblogger.class);
        weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);

        weblogsApi = new WeblogsApi();
        weblogsApi.weblogger = weblogger;

        vhostblog = aWeblog("vhostblog");
        vhostblog.setCustomDomain("vhost.example.com");
        plainblog = aWeblog("plainblog");

        // The only claimant relevant to these tests: a lookup for any other
        // hostname legitimately returns null (nobody holds it), matching a
        // fresh Mockito stub's default.
        when(weblogManager.getWeblogByCustomDomain("vhost.example.com")).thenReturn(vhostblog);
    }

    private static Weblog aWeblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        weblog.setName("Name " + handle);
        weblog.setActive(Boolean.TRUE);
        weblog.setEntryDisplayCount(15);
        return weblog;
    }

    private HttpServletRequest requestFor(String handle) {
        Weblog weblog = "vhostblog".equals(handle) ? vhostblog : plainblog;
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("actionWeblog")).thenReturn(weblog);
        return request;
    }

    private AdminDtos.WeblogView get(String handle) {
        return weblogsApi.get(requestFor(handle));
    }

    private AdminDtos.WeblogView patch(String handle, AdminDtos.WeblogPatch body) throws WebloggerException {
        return weblogsApi.update(requestFor(handle), body);
    }

    @Test
    void theWeblogViewCarriesTheCustomDomain() throws Exception {
        AdminDtos.WeblogView view = get("vhostblog");
        assertEquals("vhost.example.com", view.customDomain());
    }

    @Test
    void aPatchSetsTheCustomDomain() throws Exception {
        patch("vhostblog",
                new AdminDtos.WeblogPatch(null, null, null, null, null, null, null,
                        "moved.example.com"));
        assertEquals("moved.example.com",
                get("vhostblog").customDomain());
    }

    /** Same rules as the JSP editor, because both call CustomDomainRules. */
    @Test
    void aMalformedCustomDomainIsA400() {
        ApiException thrown = assertThrows(ApiException.class, () ->
                patch("vhostblog",
                        new AdminDtos.WeblogPatch(null, null, null, null, null, null, null,
                                "not a hostname")));
        assertEquals(400, thrown.getStatus());
    }

    @Test
    void aDuplicateCustomDomainIsA409() {
        ApiException thrown = assertThrows(ApiException.class, () ->
                patch("plainblog",
                        new AdminDtos.WeblogPatch(null, null, null, null, null, null, null,
                                "vhost.example.com")));
        assertEquals(409, thrown.getStatus());
    }

    /**
     * The uniqueness check finds the weblog itself as "the claimant" when a
     * PATCH re-submits its own already-stored domain unchanged -- that must
     * not be treated as a conflict with itself.
     */
    @Test
    void reSavingAWeblogsOwnUnchangedCustomDomainIsNotAConflict() throws Exception {
        AdminDtos.WeblogView view = patch("vhostblog",
                new AdminDtos.WeblogPatch(null, null, null, null, null, null, null,
                        "vhost.example.com"));
        assertEquals("vhost.example.com", view.customDomain());
    }
}

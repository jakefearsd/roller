package org.apache.roller.weblogger.ui.core.filters;

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.VirtualHostRegistry;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Which paths leave a custom domain for the site host.
 *
 * <p>The exempt list is the security-relevant half. ContactController is mapped
 * at /roller-ui/rendering/contact.rol and NewsletterController at
 * /newsletter/subscribe; both are posted by fetch from the rendered blog page,
 * and every bundled theme's CSP is connect-src 'self'. Redirecting either makes
 * it cross-origin -- blocked by CSP, and a 301 on a POST carries no body anyway
 * -- so every [contact] and [subscribe] shortcode on every vhost weblog would
 * silently stop working, visible only in a browser console.
 */
class ControlPlaneHostFilterTest {

    @Test
    void adminPathsBelongToTheSiteHost() {
        assertTrue(ControlPlaneHostFilter.belongsToSiteHost("/roller-ui/menu.rol"));
        assertTrue(ControlPlaneHostFilter.belongsToSiteHost("/roller-ui/authoring/entries.rol"));
        assertTrue(ControlPlaneHostFilter.belongsToSiteHost("/roller-ui/admin/globalConfig.rol"));
        assertTrue(ControlPlaneHostFilter.belongsToSiteHost("/api/v1/ping"));
    }

    @Test
    void thePublicRenderingNamespaceStays() {
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/roller-ui/rendering/contact.rol"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost(
                "/roller-ui/rendering/media-resources/blog/photo.jpg"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/roller-ui/rendering/page/blog"));
    }

    @Test
    void aNullPathBelongsNowhere() {
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost(null));
    }

    @Test
    void publicReaderEndpointsAndAssetsStay() {
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/newsletter/subscribe"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/themes/journal/style.css"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/webjars/leaflet/leaflet.js"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/robots.txt"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/sitemap.xml"));
        assertFalse(ControlPlaneHostFilter.belongsToSiteHost("/blog/entry/x"));
    }

    // --------------------------------------------------------------- doFilter
    //
    // belongsToSiteHost above is the routing rule in isolation, straight from
    // the brief. These drive the actual filter -- an unbriefed but necessary
    // companion, the same shape Task 5's implementer added for URLModelTest --
    // so the redirect, the query-string/context-path handling and, above all,
    // the redirect-loop guard are exercised by something other than a manual
    // trace: this class was otherwise going to ship with its two largest
    // methods (doFilter, siteHostUrl) completely uncovered by the diff-coverage
    // gate. WebloggerFactory is mocked exactly as URLModelTest mocks it, so
    // VirtualHostRegistry.handleFor resolves against an in-memory weblog rather
    // than the database, and site.absoluteurl is controlled directly through a
    // mocked PropertiesManager rather than a real save/restore round trip.

    private static final String VHOST = "vhost.example.com";
    private static final String SITE_URL = "https://control.example.com";

    private MockedStatic<WebloggerFactory> factory;
    private PropertiesManager properties;

    @BeforeEach
    void setUpWebloggerFactory() throws WebloggerException {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        properties = mock(PropertiesManager.class);

        Weblog vhostBlog = new Weblog("vhostblog", "creator", "VHost Blog", "desc",
                "blog@example.com", "journal", "en_US", "UTC");
        vhostBlog.setCustomDomain(VHOST);
        when(weblogManager.getWeblogs(null, null, null, null, 0, -1))
                .thenReturn(List.of(vhostBlog));
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getPropertiesManager()).thenReturn(properties);

        factory = mockStatic(WebloggerFactory.class);
        factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
        factory.when(WebloggerFactory::isBootstrapped).thenReturn(true);
        // The filter still reaches the registry through its transitional static
        // delegator (plan Task 6 injects it), which resolves it off the mocked
        // facade -- so hand the facade a registry over THIS test's mock manager.
        when(weblogger.getVirtualHostRegistry()).thenReturn(new VirtualHostRegistry(weblogManager));
    }

    @AfterEach
    void tearDownWebloggerFactory() {
        factory.close();
    }

    private void givenSiteAbsoluteUrl(String value) throws WebloggerException {
        RuntimeConfigProperty prop = value == null ? null
                : new RuntimeConfigProperty("site.absoluteurl", value);
        when(properties.getProperty("site.absoluteurl")).thenReturn(prop);
    }

    private static MockHttpServletRequest vhostRequest(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Host", VHOST);
        return request;
    }

    @Test
    void anAdminPathOnTheCustomDomainRedirectsToTheSiteHost() throws Exception {
        givenSiteAbsoluteUrl(SITE_URL);
        MockHttpServletRequest request = vhostRequest("GET", "/roller-ui/menu.rol");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneHostFilter().doFilter(request, response, chain);

        assertEquals(301, response.getStatus());
        assertEquals("https://control.example.com/roller-ui/menu.rol", response.getHeader("Location"));
        assertNull(chain.getRequest(), "a redirected request must not reach the rest of the chain");
    }

    @Test
    void anApiPathOnTheCustomDomainRedirectsToTheSiteHost() throws Exception {
        givenSiteAbsoluteUrl(SITE_URL);
        MockHttpServletRequest request = vhostRequest("GET", "/api/v1/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneHostFilter().doFilter(request, response, chain);

        assertEquals(301, response.getStatus());
        assertEquals("https://control.example.com/api/v1/ping", response.getHeader("Location"));
        assertNull(chain.getRequest());
    }

    @Test
    void theQueryStringSurvivesTheRedirect() throws Exception {
        givenSiteAbsoluteUrl(SITE_URL);
        MockHttpServletRequest request = vhostRequest("GET", "/roller-ui/authoring/entries.rol");
        request.setQueryString("weblog=vhostblog");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneHostFilter().doFilter(request, response, chain);

        assertEquals("https://control.example.com/roller-ui/authoring/entries.rol?weblog=vhostblog",
                response.getHeader("Location"));
    }

    @Test
    void aTrailingSlashOnSiteAbsoluteUrlIsNotDoubled() throws Exception {
        givenSiteAbsoluteUrl(SITE_URL + "/");
        MockHttpServletRequest request = vhostRequest("GET", "/roller-ui/menu.rol");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneHostFilter().doFilter(request, response, chain);

        assertEquals("https://control.example.com/roller-ui/menu.rol", response.getHeader("Location"));
    }

    /**
     * site.absoluteurl is configured to already include the deployment's
     * context path -- the same convention getAbsoluteContextURL() follows --
     * so the filter strips the context path from the incoming request only to
     * append it right back via the configured site url, rather than dropping
     * it from the redirect target.
     */
    @Test
    void aContextPathIsStrippedFromThePathAndReappliedViaSiteAbsoluteUrl() throws Exception {
        givenSiteAbsoluteUrl(SITE_URL + "/roller");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/roller/roller-ui/menu.rol");
        request.setContextPath("/roller");
        request.addHeader("Host", VHOST);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneHostFilter().doFilter(request, response, chain);

        assertEquals("https://control.example.com/roller/roller-ui/menu.rol",
                response.getHeader("Location"));
    }

    @Test
    void thePublicRenderingNamespaceOnTheCustomDomainIsNotRedirected() throws Exception {
        givenSiteAbsoluteUrl(SITE_URL);
        // POST, deliberately: this is exactly the shape a [contact]/[subscribe]
        // fetch takes, and the whole point is that it must reach the servlet
        // rather than receive a 301 (which would carry no body and, being
        // cross-origin, would be blocked by connect-src 'self' regardless).
        MockHttpServletRequest request = vhostRequest("POST", "/roller-ui/rendering/contact.rol");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneHostFilter().doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "the contact endpoint must reach the rest of the chain unredirected");
        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("Location"));
    }

    @Test
    void aRequestOnAHostWithNoCustomDomainIsNotRedirected() throws Exception {
        givenSiteAbsoluteUrl(SITE_URL);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/roller-ui/menu.rol");
        request.addHeader("Host", "unclaimed.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneHostFilter().doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("Location"));
    }

    /**
     * The redirect-loop guard. With site.absoluteurl unset there is no
     * host-independent statement of where the control plane lives, so the
     * filter must serve the request rather than redirect it -- redirecting via
     * getAbsoluteContextURL() here would use whatever InitFilter latched from
     * the first request after boot, which under virtual hosts can itself be a
     * custom domain, producing an infinite redirect.
     */
    @Test
    void anUnsetSiteAbsoluteUrlServesTheRequestInsteadOfRedirecting() throws Exception {
        givenSiteAbsoluteUrl(null);
        MockHttpServletRequest request = vhostRequest("GET", "/roller-ui/menu.rol");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneHostFilter().doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "a missing site.absoluteurl must degrade to pass-through, never a loop");
        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("Location"));
    }

    @Test
    void aBlankSiteAbsoluteUrlAlsoServesTheRequestInsteadOfRedirecting() throws Exception {
        givenSiteAbsoluteUrl("   ");
        MockHttpServletRequest request = vhostRequest("GET", "/roller-ui/menu.rol");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneHostFilter().doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertNull(response.getHeader("Location"));
    }
}

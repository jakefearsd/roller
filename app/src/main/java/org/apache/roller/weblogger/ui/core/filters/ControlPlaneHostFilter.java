package org.apache.roller.weblogger.ui.core.filters;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.business.VirtualHostRegistry;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;

/**
 * Sends admin and automation-API requests that arrived on a weblog's custom
 * domain back to the site host, so there is one session cookie, one login and
 * one API base url however many weblogs exist.
 *
 * <p>Registered at filter order 35 -- ahead of the Spring Security chain (40),
 * so an unauthenticated admin request is moved to the right host before
 * security can mint a session and a CSRF token on the wrong one.
 */
public class ControlPlaneHostFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneHostFilter.class);

    /** The public rendering namespace, which must stay on the custom domain. */
    private static final String PUBLIC_RENDERING = "/roller-ui/rendering/";

    /**
     * True when this path is control plane and belongs on the site host.
     * Package-private-plus for the test; the ordering of the two /roller-ui
     * checks is the whole rule.
     */
    public static boolean belongsToSiteHost(String path) {
        if (path == null) {
            return false;
        }
        if (path.startsWith(PUBLIC_RENDERING)) {
            return false;
        }
        return path.startsWith("/roller-ui/") || "/roller-ui".equals(path)
                || path.startsWith("/api/") || "/api".equals(path);
    }

    @Override
    @SuppressFBWarnings(
            value = "BC_UNCONFIRMED_CAST",
            justification = "This application is deployed exclusively over HTTP -- every filter is "
                    + "registered via a FilterRegistrationBean against Spring Boot's embedded Tomcat "
                    + "HTTP connector (ServletRegistrationConfig), and no other protocol connector is "
                    + "ever configured. The servlet container therefore guarantees doFilter is invoked "
                    + "only with HttpServletRequest/HttpServletResponse; the cast is unconfirmed only "
                    + "under the general, protocol-independent Filter contract, not in this deployment.")
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();
        if (request.getContextPath() != null && !request.getContextPath().isEmpty()) {
            path = path.substring(request.getContextPath().length());
        }

        if (belongsToSiteHost(path)
                && VirtualHostRegistry.handleFor(request.getHeader("Host")) != null) {

            String siteUrl = siteHostUrl();
            if (siteUrl == null) {
                // No host-independent statement of where the control plane
                // lives. Redirecting to getAbsoluteContextURL() here would use
                // whatever InitFilter latched from the first request after
                // boot -- which under virtual hosts can be a custom domain,
                // producing an infinite redirect. Degrade to pre-vhost
                // behaviour instead: serve the request.
                log.warn("A weblog has a custom domain but site.absoluteurl is unset; "
                        + "serving {} on the custom domain rather than "
                        + "redirecting. Set site.absoluteurl to enable the "
                        + "control-plane boundary.", path);
                chain.doFilter(req, res);
                return;
            }

            StringBuilder target = new StringBuilder(siteUrl).append(path);
            if (request.getQueryString() != null) {
                target.append('?').append(request.getQueryString());
            }
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader("Location", target.toString());
            return;
        }

        chain.doFilter(req, res);
    }

    /**
     * The site host, read from site.absoluteurl DIRECTLY and never through
     * getAbsoluteContextURL(), which falls back to InitFilter's latched value.
     */
    private static String siteHostUrl() {
        String configured = WebloggerRuntimeConfig.getProperty("site.absoluteurl");
        if (configured == null || configured.isBlank()) {
            configured = WebloggerConfig.getProperty("site.absoluteurl");
        }
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return configured.endsWith("/")
                ? configured.substring(0, configured.length() - 1)
                : configured;
    }
}

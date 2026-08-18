package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.CustomDomainRules;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ColumnLimits;
import org.apache.roller.weblogger.ui.restapi.auth.AdminScoped;
import org.apache.roller.weblogger.ui.restapi.dto.AdminDtos;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site-wide weblog administration: list every weblog on the site, and view
 * or edit any one of them by handle -- not the weblog-scoped content APIs
 * ({@code EntriesApi}, {@code CategoriesApi}, {@code MediaApi}), which act
 * only on the weblog a token is scoped to. {@code UISecurityEnforced}
 * declares {@code GlobalPermission.ADMIN} (not a {@code WeblogPermission}):
 * this reaches ANY weblog by handle regardless of who owns it, the same
 * capability {@code GET/PATCH /v1/admin/users} has over accounts, just
 * addressed as {@code /v1/weblogs/{handle}} rather than
 * {@code /v1/admin/weblogs/{handle}} because the weblog -- not "admin" -- is
 * the REST resource. {@code isWeblogRequired()} is {@code false}: the list
 * endpoint carries no {@code {handle}}, so the interceptor must never demand
 * one before dispatching there; {@code get}/{@code update} resolve their own
 * {@code {handle}} through {@link BaseApiController#requireActionWeblog}
 * regardless.
 *
 * <p><b>{@code @AdminScoped} despite living outside {@code /v1/admin}.</b>
 * {@code AdminScopedCoverageTest} does not require it here -- it only scans
 * for the {@code /v1/admin} URL prefix -- but the capability is exactly as
 * sensitive as {@code AdminApi}'s: a token minted for routine, single-weblog
 * automation (POST-role, scoped to one weblog) must not be able to use this
 * controller to reconfigure or enumerate every weblog on the site just
 * because the user behind it happens to hold {@code GlobalPermission.ADMIN}.
 * {@code ApiScopeInterceptor} reads {@code @AdminScoped} off the class, not
 * the route, so it applies here the same as on {@code AdminApi}/{@code
 * AdminActionsApi}.
 */
@RestController
@RequestMapping("/v1/weblogs")
@AdminScoped
public class WeblogsApi extends BaseApiController implements UISecurityEnforced {

    private static final int MAX_LIMIT = 200;

    @GetMapping("")
    public List<AdminDtos.WeblogView> list(
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {
        // Same negative-limit/offset trap as every other list endpoint in
        // this API -- see EntriesApi.list and AdminApi.listUsers.
        if (limit < 1 || offset < 0) {
            throw ApiException.badRequest("limit must be at least 1 and offset must not be negative.");
        }
        int boundedLimit = Math.min(limit, MAX_LIMIT);

        List<AdminDtos.WeblogView> views = new ArrayList<>();
        for (Weblog weblog : weblogger.getWeblogManager()
                .getWeblogs(null, null, null, null, offset, boundedLimit)) {
            views.add(AdminDtos.toView(weblog));
        }
        return views;
    }

    @GetMapping("/{handle}")
    public AdminDtos.WeblogView get(HttpServletRequest request) {
        return AdminDtos.toView(requireActionWeblog(request));
    }

    @PatchMapping("/{handle}")
    public AdminDtos.WeblogView update(HttpServletRequest request, @RequestBody AdminDtos.WeblogPatch body)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);

        if (body.name() != null) {
            String name = body.name().trim();
            if (name.isBlank()) {
                throw ApiException.badRequest("name cannot be blank.");
            }
            ColumnLimits.requireMaxLength("name", name, ColumnLimits.WEBLOG_NAME);
            weblog.setName(name);
        }
        if (body.tagline() != null) {
            ColumnLimits.requireMaxLength("tagline", body.tagline(), ColumnLimits.TAGLINE);
            weblog.setTagline(body.tagline());
        }
        if (body.emailAddress() != null) {
            ColumnLimits.requireMaxLength(
                    "emailAddress", body.emailAddress(), ColumnLimits.WEBLOG_EMAIL_ADDRESS);
            weblog.setEmailAddress(body.emailAddress());
        }
        if (body.locale() != null) {
            ColumnLimits.requireMaxLength("locale", body.locale(), ColumnLimits.LOCALE);
            weblog.setLocale(body.locale());
        }
        if (body.timeZone() != null) {
            ColumnLimits.requireMaxLength("timeZone", body.timeZone(), ColumnLimits.TIME_ZONE);
            weblog.setTimeZone(body.timeZone());
        }
        if (body.entryDisplayCount() != null) {
            // Same bound WeblogConfigController.myValidate enforces for the
            // JSP form: entryDisplayCount above the site-wide cap is refused
            // rather than silently accepted and then behaving strangely on
            // every page that reads it.
            int maxEntries = WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries");
            if (body.entryDisplayCount() < 1 || (maxEntries > 0 && body.entryDisplayCount() > maxEntries)) {
                throw ApiException.badRequest(
                        "entryDisplayCount must be between 1 and " + maxEntries + ".");
            }
            weblog.setEntryDisplayCount(body.entryDisplayCount());
        }
        if (body.active() != null) {
            weblog.setActive(body.active());
        }
        if (body.customDomain() != null) {
            // Same rule as WeblogConfigController.myValidate for the JSP
            // editor -- calling CustomDomainRules here rather than
            // reimplementing the hostname check is the whole point of that
            // class existing. An explicit blank clears the domain (normalise
            // turns "" into null); a JSON-null field (the body.customDomain()
            // != null guard above) leaves it untouched, same as every other
            // patchable field on this endpoint.
            String customDomain = CustomDomainRules.normalise(body.customDomain());
            if (customDomain != null) {
                if (!CustomDomainRules.isWellFormed(customDomain)) {
                    throw ApiException.badRequest("customDomain must be a well-formed hostname.");
                }
                // Same I4b rule as WeblogConfigController.myValidate for the
                // JSP editor -- reject the site's own hostname, or
                // VirtualHostRegistry resolves it to this weblog for every
                // request, including /roller-ui/**, which
                // ControlPlaneHostFilter then redirects back to the host it
                // just arrived on: an infinite loop with no route back.
                if (CustomDomainRules.isSiteHost(customDomain,
                        WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))) {
                    throw ApiException.badRequest("customDomain must not be the site's own hostname.");
                }
                Weblog claimant = weblogger.getWeblogManager().getWeblogByCustomDomain(customDomain);
                if (claimant != null && !claimant.getHandle().equals(weblog.getHandle())) {
                    throw ApiException.conflict("customDomain is already in use by another weblog.");
                }
            }
            weblog.setCustomDomain(customDomain);
        }

        weblogger.getWeblogManager().saveWeblog(weblog);
        weblogger.flush();
        // WeblogPageCache has no CacheHandler and expires only lazily
        // against weblog.lastModified (see CLAUDE.md's Templates section);
        // CacheManager.invalidate is what bumps it, the same call
        // WeblogConfigController's own save makes.
        CacheManager.invalidate(weblog);
        return AdminDtos.toView(weblog);
    }

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of();
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }
}

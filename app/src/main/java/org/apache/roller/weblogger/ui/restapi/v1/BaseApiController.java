package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.controllers.WeblogOwnership;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

/**
 * Shared plumbing for every {@code /v1/weblogs/{handle}/...} controller.
 *
 * <p>{@code RollerHandlerInterceptor} has already resolved the {@code
 * {handle}} path variable to a {@link Weblog} and enforced the controller's
 * declared {@code UISecurityEnforced} permission before any handler method
 * here is ever invoked -- see {@link #requireActionWeblog} -- so nothing in
 * this class or its subclasses does permission checking of its own.
 */
public abstract class BaseApiController {

    @Autowired
    @Lazy
    protected Weblogger weblogger;

    /**
     * The weblog this request acts on, resolved by
     * {@code RollerHandlerInterceptor} from the {@code {handle}} URI
     * template variable and stashed as the {@code actionWeblog} request
     * attribute. Missing only when the handle named no weblog at all --
     * everything downstream of that (does the caller have permission on it)
     * is already enforced by the interceptor before this method runs.
     */
    protected Weblog requireActionWeblog(HttpServletRequest request) {
        Object weblog = request.getAttribute("actionWeblog");
        if (weblog instanceof Weblog w) {
            return w;
        }
        throw ApiException.notFound("No such weblog.");
    }

    /**
     * The entry with this id, but only when it belongs to the action
     * weblog. Delegates to {@link WeblogOwnership#entry} -- this codebase's
     * one IDOR defense for by-id entry lookups -- and throws 404 rather
     * than returning null, since every caller here wants exactly that.
     */
    protected WeblogEntry requireEntry(HttpServletRequest request, String id) {
        WeblogEntry entry = WeblogOwnership.entry(weblogger, id, requireActionWeblog(request));
        if (entry == null) {
            throw ApiException.notFound("No such entry.");
        }
        return entry;
    }
}

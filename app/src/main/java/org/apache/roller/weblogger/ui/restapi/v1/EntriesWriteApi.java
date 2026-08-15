package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Entry create and patch. A separate {@code @RestController} from {@link
 * EntriesApi} on the same {@code /v1/weblogs/{handle}/entries} path --
 * {@code UISecurityEnforced} declares its required {@code WeblogPermission}
 * per controller, not per method, and reads (EDIT_DRAFT) and writes (POST)
 * need different ones. Spring MVC resolves by method plus path, so GET
 * reaches {@link EntriesApi} and POST/PATCH reach this controller with no
 * ambiguity; {@code RollerHandlerInterceptor} is what actually enforces the
 * declared permission, so this controller adds no permission checking of
 * its own.
 */
@RestController
@RequestMapping("/v1/weblogs/{handle}/entries")
public class EntriesWriteApi extends BaseApiController implements UISecurityEnforced {

    @PostMapping("")
    public ResponseEntity<EntryDtos.EntryView> create(
            HttpServletRequest request, @RequestBody EntryDtos.EntryWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        User user = requireAuthenticatedUser(request);

        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setCreatorUserName(user.getUserName());

        EntryDtos.applyWrite(entry, body, weblog);
        applyCategory(entry, weblog, body.category());
        applyTags(entry, body.tags());

        // No explicit category fallback needed here:
        // JPAWeblogEntryManagerImpl.saveWeblogEntry already defaults a
        // categoryless entry to the weblog's first category -- the same
        // fallback CategoriesApi.delete relies on to guarantee at least one
        // category always exists.
        weblogger.getWeblogEntryManager().saveWeblogEntry(entry);
        weblogger.flush();

        URI location = ServletUriComponentsBuilder.fromRequestUri(request)
                .path("/{id}")
                .buildAndExpand(entry.getId())
                .toUri();
        return ResponseEntity.created(location).body(EntryDtos.toView(entry, permalink(entry)));
    }

    @PatchMapping("/{id}")
    public EntryDtos.EntryView update(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody EntryDtos.EntryWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        WeblogEntry entry = requireEntry(request, id);

        EntryDtos.applyWrite(entry, body, weblog);
        applyCategory(entry, weblog, body.category());
        applyTags(entry, body.tags());

        weblogger.getWeblogEntryManager().saveWeblogEntry(entry);
        weblogger.flush();

        return EntryDtos.toView(entry, permalink(entry));
    }

    /**
     * {@code category} names a category by NAME, matching {@code
     * EntryDtos.EntryView.category} -- the read side already exposes the
     * name rather than an internal id, so the write side accepts the same
     * thing back. {@code getWeblogCategoryByName} is scoped to {@code
     * weblog} in the query itself, the same lookup {@code CategoriesApi}
     * uses for its own rename-collision check, so an unknown name is simply
     * absent -- there is no separate ownership check to bypass, unlike an
     * by-id lookup.
     *
     * <p>Null (absent from the request) leaves the entry's category
     * untouched, per {@code applyWrite}'s "null means absent" contract.
     */
    private void applyCategory(WeblogEntry entry, Weblog weblog, String categoryName)
            throws WebloggerException {
        if (categoryName == null) {
            return;
        }
        String trimmed = categoryName.trim();
        if (trimmed.isBlank()) {
            throw ApiException.badRequest("category cannot be blank.");
        }
        WeblogCategory category = weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, trimmed);
        if (category == null) {
            throw ApiException.badRequest("Unknown category '" + categoryName + "'.");
        }
        entry.setCategory(category);
    }

    /**
     * Tags go through the entry's own comma/space-tolerant tag-setting path
     * rather than being reimplemented here. Null (absent) leaves the
     * stored tags untouched; a non-null EMPTY list is a real instruction to
     * clear every tag, distinguishable from absence exactly as {@code
     * applyWrite} distinguishes them for every other field.
     */
    private void applyTags(WeblogEntry entry, List<String> tags) throws WebloggerException {
        if (tags == null) {
            return;
        }
        entry.setTagsAsString(String.join(" ", tags));
    }

    /**
     * The request's {@code authenticatedUser} attribute, set by {@code
     * RollerHandlerInterceptor} before any handler here runs -- {@link
     * #isUserRequired()} is true, so a request that reached this method
     * already has one; the check exists only so a missing attribute fails
     * loudly rather than with a NullPointerException.
     */
    private User requireAuthenticatedUser(HttpServletRequest request) {
        Object user = request.getAttribute("authenticatedUser");
        if (user instanceof User u) {
            return u;
        }
        throw ApiException.unauthorized("Not authenticated.");
    }

    private String permalink(WeblogEntry entry) {
        return weblogger.getUrlStrategy()
                .getWeblogEntryURL(entry.getWebsite(), null, entry.getAnchor(), true);
    }

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return true;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of(WeblogPermission.POST);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of();
    }
}

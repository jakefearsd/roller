/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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

package org.apache.roller.weblogger.ui.controllers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.ui.core.util.menu.Menu;
import org.apache.roller.weblogger.ui.core.util.menu.MenuHelper;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Abstract base controller for Spring MVC controllers in Roller.
 * Replaces the Struts 2 UIAction base class, providing common utilities
 * for authentication, authorization, message resolution, and model population.
 */
public abstract class BaseController implements UISecurityEnforced, UIActionPreparable {

    private static final Log baseLog = LogFactory.getLog(BaseController.class);

    @Autowired
    protected MessageSource messageSource;

    // @Lazy is load-bearing: controllers are constructed at context refresh,
    // before WebloggerStartup.prepare() has run. The lazy proxy defers
    // building the business-tier graph to first use rather than at wiring time.
    @Autowired
    @org.springframework.context.annotation.Lazy
    protected Weblogger weblogger;

    /**
     * Allow form parameters prefixed with "bean." to bind to @ModelAttribute("bean").
     * This preserves the Struts2 convention where form fields were named bean.title, bean.id, etc.
     * Controllers using Spring form:form tags (which don't use the prefix) should override
     * this method with a no-op.
     *
     * <p><strong>Checkbox field markers ("_xxx" hidden inputs) must name the
     * bean's real property path, not the "bean."-prefixed one.</strong>
     * {@code WebDataBinder.doBind} runs {@code checkFieldDefaults} (which
     * strips this "bean." prefix) BEFORE {@code checkFieldMarkers}, and
     * {@code checkFieldDefaults} only rewrites parameters that literally
     * start with "bean." -- a marker named e.g. {@code "_bean.showInNav"}
     * starts with {@code "_"}, so it is left alone, and
     * {@code checkFieldMarkers} then looks for a writable property literally
     * named {@code "bean.showInNav"}, which does not exist, so the marker is
     * silently discarded and an unchecked box never binds to {@code false}.
     * The marker must be named {@code "_showInNav"} (see PageEdit.jsp).
     */
    @InitBinder("bean")
    public void initBeanBinder(WebDataBinder binder) {
        binder.setFieldDefaultPrefix("bean.");
    }

    // --- UIActionPreparable default ---

    @Override
    public void myPrepare() {
        // no-op by default; subclasses override as needed
    }

    // --- UISecurityEnforced defaults (safe: require login + weblog admin) ---

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
        return List.of(WeblogPermission.ADMIN);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.LOGIN);
    }

    // --- Request attribute accessors ---

    /**
     * Get the authenticated user from the request attributes (set by the interceptor).
     */
    protected User getAuthenticatedUser(HttpServletRequest request) {
        return (User) request.getAttribute("authenticatedUser");
    }

    /**
     * Get the action weblog from the request attributes (set by the interceptor).
     */
    protected Weblog getActionWeblog(HttpServletRequest request) {
        return (Weblog) request.getAttribute("actionWeblog");
    }

    // --- Message / i18n helpers ---

    /**
     * Resolve a message from the message source using the request locale.
     */
    protected String getText(String key, HttpServletRequest request) {
        return messageSource.getMessage(key, null, key, request.getLocale());
    }

    /**
     * Resolve a message with arguments from the message source using the request locale.
     */
    protected String getText(String key, Object[] args, HttpServletRequest request) {
        return messageSource.getMessage(key, args, key, request.getLocale());
    }

    // --- Model error / message helpers ---

    /**
     * Add an error message (resolved from the message source) to the model.
     */
    protected void addError(Model model, String key, HttpServletRequest request) {
        addToModel(model, "errors", getText(key, request));
    }

    /**
     * Add an error message (resolved from the message source) with a parameter to the model.
     */
    protected void addError(Model model, String key, String param, HttpServletRequest request) {
        addToModel(model, "errors", getText(key, new Object[]{param}, request));
    }

    /**
     * Add an error message (resolved from the message source) with arbitrary
     * arguments to the model.
     *
     * <p>For a key carrying more than one placeholder -- or whose arguments
     * are already collected as an {@code Object[]}/{@code String[]}, e.g. a
     * {@link org.apache.roller.weblogger.util.RollerMessages.RollerMessage}
     * replayed from a validation collector -- the single-{@code param}
     * overload above cannot carry them, and dropping them silently returns
     * the raw, unresolved {@code {0}}/{@code {1}} pattern text: {@code
     * alwaysUseMessageFormat} is {@code false} (see {@code
     * MessageFormatRegressionTest}), so a no-args lookup never runs the
     * pattern through {@code MessageFormat} at all.
     */
    protected void addError(Model model, String key, Object[] args, HttpServletRequest request) {
        addToModel(model, "errors", getText(key, args, request));
    }

    /**
     * Check whether any error messages have been added to the model.
     */
    @SuppressWarnings("unchecked")
    protected boolean hasErrors(Model model) {
        List<String> errors = (List<String>) model.getAttribute("errors");
        return errors != null && !errors.isEmpty();
    }

    /**
     * Add a status message (resolved from the message source) to the model.
     */
    protected void addMessage(Model model, String key, HttpServletRequest request) {
        addToModel(model, "messages", getText(key, request));
    }

    /**
     * Add a status message (resolved from the message source) with a parameter to the model.
     */
    protected void addMessage(Model model, String key, String param, HttpServletRequest request) {
        addToModel(model, "messages", getText(key, new Object[]{param}, request));
    }

    // --- Flash attribute helpers (for messages that survive redirects) ---

    /**
     * Add a flash message that survives a redirect.
     */
    protected void addFlashMessage(RedirectAttributes redirectAttributes, String key, HttpServletRequest request) {
        addToFlash(redirectAttributes, "messages", getText(key, request));
    }

    /**
     * Add a flash message with a parameter that survives a redirect.
     */
    protected void addFlashMessage(RedirectAttributes redirectAttributes, String key, String param, HttpServletRequest request) {
        addToFlash(redirectAttributes, "messages", getText(key, new Object[]{param}, request));
    }

    /**
     * Add a flash error that survives a redirect.
     */
    protected void addFlashError(RedirectAttributes redirectAttributes, String key, HttpServletRequest request) {
        addToFlash(redirectAttributes, "errors", getText(key, request));
    }

    /**
     * Add a flash error with a parameter that survives a redirect.
     */
    protected void addFlashError(RedirectAttributes redirectAttributes, String key, String param, HttpServletRequest request) {
        addToFlash(redirectAttributes, "errors", getText(key, new Object[]{param}, request));
    }

    @SuppressWarnings("unchecked")
    private void addToModel(Model model, String attrName, String text) {
        List<String> list = (List<String>) model.getAttribute(attrName);
        if (list == null) {
            list = new ArrayList<>();
            model.addAttribute(attrName, list);
        }
        list.add(text);
    }

    @SuppressWarnings("unchecked")
    private void addToFlash(RedirectAttributes redirectAttributes, String attrName, String text) {
        List<String> list = (List<String>) redirectAttributes.getFlashAttributes().get(attrName);
        if (list == null) {
            list = new ArrayList<>();
        }
        list.add(text);
        redirectAttributes.addFlashAttribute(attrName, list);
    }

    // --- Entry lookup ---

    /**
     * The entry with this id, but only when it belongs to the action weblog.
     *
     * <p>{@code getWeblogEntry} is a global by-id lookup and the id always
     * arrives as client input, while the permission interceptor only
     * establishes that the caller may edit the <em>action</em> weblog. Every
     * caller that resolves an entry id -- read and write alike -- goes through
     * here: the editor page hangs an entry's share URL (a durable credential
     * for an unpublished draft) off whatever this resolves, and the list
     * actions delete and rewrite whatever it returns.
     *
     * <p>An unknown id and a foreign one are deliberately indistinguishable to
     * the caller, so a probe cannot map one weblog's entry ids from another.
     */
    protected WeblogEntry lookupEntry(String id, HttpServletRequest request) {
        // Blank as well as null: an empty id names nothing, and some managers
        // read an empty string as a wildcard rather than a miss.
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            WeblogEntry entry = weblogger.getWeblogEntryManager().getWeblogEntry(id);
            if (entry == null || entry.getWebsite() == null
                    || !entry.getWebsite().equals(getActionWeblog(request))) {
                return null;
            }
            return entry;
        } catch (WebloggerException ex) {
            baseLog.error("Error looking up entry by id - " + id, ex);
        }
        return null;
    }

    /**
     * The entry with this id, but refusing one that has been trashed -- the
     * counterpart to {@code TrashController}'s own {@code trashedEntry} check,
     * which refuses everything that is <em>not</em> trashed. This refuses
     * everything that <em>is</em>.
     *
     * <p>{@link #lookupEntry} carries the ownership check but no status
     * filter, so without this a bookmarked editor URL, a forged
     * {@code duplicateId}, or a trashed id slipped into a bulk-action
     * selection would resolve a trashed entry as if it were live. That
     * matters because every one of those seams can end in a save: the
     * editor's Publish button, the duplicate action's copy-and-open, and
     * bulk actions like {@code bulkPublish} all end up calling
     * {@code saveWeblogEntry}, which would resurrect the entry to
     * {@code DRAFT} (or straight to {@code PUBLISHED}) by a side door that
     * bypasses restore entirely -- and does so while {@code trashedAt} is
     * still populated on the row.
     *
     * <p>An unknown id, a foreign id, and a trashed id are deliberately
     * indistinguishable to the caller, the same way {@link #lookupEntry}
     * already keeps an unknown id and a foreign one indistinguishable.
     */
    protected WeblogEntry lookupNonTrashedEntry(String id, HttpServletRequest request) {
        WeblogEntry entry = lookupEntry(id, request);
        if (entry == null || entry.getStatus() == PubStatus.TRASHED) {
            return null;
        }
        return entry;
    }

    /**
     * Moves an entry to the trash, taking it out of everything that indexes
     * or caches it. This is the authoring UI's single deletion seam -- the
     * entry list's bulk action and both entry-removal endpoints all call
     * this, never {@link WeblogEntryManager#trashWeblogEntry} directly.
     *
     * <p>The order matters and the steps are not optional, and staying
     * TRASHED rather than being removed from the database does not make them
     * unnecessary -- the opposite: the search index holds documents keyed by
     * entry id, so a TRASHED entry left in it is still findable by site
     * search and still links to a page that now 404s, exactly the failure a
     * genuine delete would produce. {@code CacheManager.invalidate} then
     * clears the rendered pages that contained it.
     *
     * <p>Indexing failures are logged and swallowed rather than aborting the
     * trash: an index that has fallen behind is repairable from the admin
     * screen, whereas a half-trashed entry is not.
     */
    protected void trashEntryWithIndex(WeblogEntry entry) throws WebloggerException {
        deIndexAndInvalidate(entry);
        weblogger.getWeblogEntryManager().trashWeblogEntry(entry);
    }

    /**
     * Permanently deletes an entry -- the "delete forever" action on an
     * already-trashed entry -- taking it out of everything that indexes or
     * caches it first.
     *
     * <p>Same index/cache steps as {@link #trashEntryWithIndex} and for the
     * same reason (see its javadoc); the only difference is the final call,
     * which here is the actual, irreversible {@link WeblogEntryManager#removeWeblogEntry}
     * rather than a trash. A trashed entry is normally already out of the
     * index -- it was de-indexed on the way into the trash -- but this
     * re-runs the same dance rather than assuming that, since nothing
     * prevents a caller from reaching this on an entry that never went
     * through the trash path.
     */
    protected void deleteEntryForeverWithIndex(WeblogEntry entry) throws WebloggerException {
        deIndexAndInvalidate(entry);
        weblogger.getWeblogEntryManager().removeWeblogEntry(entry);
    }

    /**
     * The shared index/cache step {@link #trashEntryWithIndex} and
     * {@link #deleteEntryForeverWithIndex} both need before their differing
     * final step: take the entry out of the search index, then invalidate
     * the render cache.
     *
     * <p>This used to flip the entry's in-memory status to DRAFT and hand it
     * to {@code addEntryReIndexOperation} on the theory that a re-index
     * would teach the index to drop it. That never worked: the re-index runs
     * on a background thread and re-fetches the entry from the database by
     * id before doing anything, so the caller's in-memory flip was discarded
     * before the job ever ran, and the entry went right back into the index
     * moments after this method returned -- a TRASHED entry, findable again
     * by site search, linking to a page that 404s. The honest operation here
     * is "remove this document from the index", not "re-index it as a draft
     * and hope", so this now calls {@link IndexManager#removeEntryIndexOperation}
     * directly and unconditionally. It runs synchronously (in the foreground,
     * not scheduled on a background thread) and is safe to call on an entry
     * that was never published -- deleting a document that was never indexed
     * is a no-op.
     */
    private void deIndexAndInvalidate(WeblogEntry entry) {
        try {
            weblogger.getIndexManager().removeEntryIndexOperation(entry);
        } catch (WebloggerException ex) {
            baseLog.warn("Trouble removing entry from the search index for " + entry.getId(), ex);
        }

        CacheManager.invalidate(entry);
    }

    /**
     * The template with this id, but only when it belongs to the action weblog.
     *
     * <p>Exactly the same hazard as {@link #lookupEntry}, and for exactly the
     * same reason: {@code getTemplate} is a global by-id lookup while the
     * permission interceptor only establishes that the caller may edit the
     * <em>action</em> weblog. A template id arrives as client input, so without
     * this an editor on one weblog can read, overwrite and delete another
     * weblog's theme templates -- which on a shared install means writing
     * arbitrary markup into somebody else's public pages.
     *
     * <p>An unknown id and a foreign one are deliberately indistinguishable.
     */
    protected WeblogTemplate lookupTemplate(String id, HttpServletRequest request) {
        // Blank as well as null: an empty id names nothing, and some managers
        // read an empty string as a wildcard rather than a miss.
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            WeblogTemplate template = weblogger.getWeblogManager().getTemplate(id);
            if (template == null || template.getWeblog() == null
                    || !template.getWeblog().equals(getActionWeblog(request))) {
                return null;
            }
            return template;
        } catch (WebloggerException ex) {
            baseLog.error("Error looking up template by id - " + id, ex);
        }
        return null;
    }

    /**
     * The category with this id, but only when it belongs to the action weblog.
     *
     * <p>The third of the same hazard, after {@link #lookupEntry} and
     * {@link #lookupTemplate}: {@code getWeblogCategory} is a global by-id
     * lookup, the permission interceptor only establishes that the caller may
     * edit the <em>action</em> weblog, and the id arrives as client input.
     * Without this, an editor on one weblog could rename another weblog's
     * categories, delete them, or name one as the destination for a move --
     * silently re-filing somebody else's posts.
     *
     * <p>An unknown id and a foreign one are deliberately indistinguishable.
     */
    protected WeblogCategory lookupCategory(String id, HttpServletRequest request) {
        // Blank as well as null: an empty id names nothing, and some managers
        // read an empty string as a wildcard rather than a miss.
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            WeblogCategory category = weblogger.getWeblogEntryManager().getWeblogCategory(id);
            if (category == null || category.getWeblog() == null
                    || !category.getWeblog().equals(getActionWeblog(request))) {
                return null;
            }
            return category;
        } catch (WebloggerException ex) {
            baseLog.error("Error looking up category by id - " + id, ex);
        }
        return null;
    }

    /**
     * The page named by {@code id}, but only when it belongs to the weblog
     * this action is scoped to. The fourth of the same hazard, after
     * {@link #lookupEntry}, {@link #lookupTemplate} and {@link #lookupCategory}:
     * {@code getPage} is a global by-id lookup, the permission interceptor only
     * vouches for the <em>action</em> weblog, and the id arrives as client
     * input. A blank id is absent, not something to look up.
     *
     * <p>Public rather than {@code protected} like its three siblings, so that
     * {@code PageEditControllerTest} -- in a different package -- can pin the
     * blank-id guard directly, the same way the ownership check itself is
     * pinned through the controller's own {@code edit}/{@code save} methods.
     */
    public WeblogPage lookupPage(String id, HttpServletRequest request) {
        if (StringUtils.isBlank(id)) {
            return null;
        }
        try {
            WeblogPage page = weblogger.getWeblogPageManager().getPage(id);
            if (page == null || page.getWeblog() == null
                    || !page.getWeblog().equals(getActionWeblog(request))) {
                return null;
            }
            return page;
        } catch (WebloggerException ex) {
            baseLog.error("Error looking up page by id - " + id, ex);
        }
        return null;
    }

    // --- Configuration property helpers ---

    /**
     * Get a configuration property value. Checks static config first,
     * then runtime config. Returns the key itself if not found.
     */
    protected String getProp(String key) {
        String value = WebloggerConfig.getProperty(key);
        if (value == null) {
            value = WebloggerRuntimeConfig.getProperty(key);
        }
        return (value == null) ? key : value;
    }

    /**
     * Get a boolean configuration property value. Checks static config first,
     * then runtime config. Returns false if not found.
     */
    protected boolean getBooleanProp(String key) {
        String value = WebloggerConfig.getProperty(key);
        if (value == null) {
            value = WebloggerRuntimeConfig.getProperty(key);
        }
        return (value != null) && Boolean.parseBoolean(value);
    }

    // --- Common model population ---

    /**
     * Populate common model attributes used across all pages:
     * authenticatedUser, actionWeblog, pageTitle, siteURL, absoluteSiteURL, menu.
     */
    protected void populateCommonModel(HttpServletRequest request, Model model) {
        User user = getAuthenticatedUser(request);
        Weblog weblog = getActionWeblog(request);

        model.addAttribute("authenticatedUser", user);
        model.addAttribute("actionWeblog", weblog);
        model.addAttribute("pageTitle", getPageTitle());
        model.addAttribute("siteURL", WebloggerRuntimeConfig.getRelativeContextURL());
        model.addAttribute("absoluteSiteURL", WebloggerRuntimeConfig.getAbsoluteContextURL());
        model.addAttribute("actionName", getActionName());
        model.addAttribute("desiredMenu", getDesiredMenu());

        // build menu if applicable
        Menu menu = MenuHelper.getMenu(getDesiredMenu(), getActionName(), user, weblog);
        if (menu != null) {
            model.addAttribute("menu", menu);
        }
    }

    // --- Overridable metadata ---

    /**
     * Returns the page title for the current action. Subclasses should override
     * to provide a meaningful title (typically a message key).
     */
    public String getPageTitle() {
        return "";
    }

    /**
     * Returns the name of the menu this action wants to display, or null for no menu.
     */
    public String getDesiredMenu() {
        return null;
    }

    /**
     * Returns the action name used for menu highlighting. Subclasses should override.
     */
    public String getActionName() {
        return null;
    }
}

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
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.ui.controllers.util.KeyValueObject;
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
     * Deletes an entry and everything that indexes or caches it.
     *
     * <p>The order matters and the steps are not optional. The search index
     * holds documents keyed by entry id, so an entry removed from the database
     * without {@code removeEntryIndexOperation} leaves a document that still
     * matches searches and links to a page that 404s. The re-index of the
     * entry as a DRAFT first is how the index learns to drop it while the
     * entry still exists to be read. {@code CacheManager.invalidate} then
     * clears the rendered pages that contained it.
     *
     * <p>Indexing failures are logged and swallowed rather than aborting the
     * delete: an index that has fallen behind is repairable from the admin
     * screen, whereas a half-deleted entry is not.
     */
    protected void removeEntryWithIndex(WeblogEntry entry) throws WebloggerException {
        IndexManager indexManager = weblogger.getIndexManager();
        try {
            WeblogEntry.PubStatus originalStatus = entry.getStatus();
            entry.setStatus(WeblogEntry.PubStatus.DRAFT);
            indexManager.addEntryReIndexOperation(entry);
            entry.setStatus(originalStatus);

            if (entry.isPublished()) {
                indexManager.removeEntryIndexOperation(entry);
            }
        } catch (WebloggerException ex) {
            baseLog.warn("Trouble triggering entry indexing for " + entry.getId(), ex);
        }

        CacheManager.invalidate(entry);
        weblogger.getWeblogEntryManager().removeWeblogEntry(entry);
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

    // --- Comment management helpers ---

    /**
     * Returns the list of comment status filter options for comment management pages.
     */
    protected List<KeyValueObject> getCommentStatusOptions(HttpServletRequest request) {
        List<KeyValueObject> opts = new ArrayList<>();
        opts.add(new KeyValueObject("ALL", getText("generic.all", request)));
        opts.add(new KeyValueObject("ONLY_PENDING", getText("commentManagement.onlyPending", request)));
        opts.add(new KeyValueObject("ONLY_APPROVED", getText("commentManagement.onlyApproved", request)));
        opts.add(new KeyValueObject("ONLY_DISAPPROVED", getText("commentManagement.onlyDisapproved", request)));
        return opts;
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

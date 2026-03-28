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

import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.core.util.menu.Menu;
import org.apache.roller.weblogger.ui.core.util.menu.MenuHelper;
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

    @Autowired
    protected MessageSource messageSource;

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
    @SuppressWarnings("unchecked")
    protected void addError(Model model, String key, HttpServletRequest request) {
        List<String> errors = (List<String>) model.getAttribute("errors");
        if (errors == null) {
            errors = new ArrayList<>();
            model.addAttribute("errors", errors);
        }
        errors.add(getText(key, request));
    }

    /**
     * Add an error message (resolved from the message source) with a parameter to the model.
     */
    @SuppressWarnings("unchecked")
    protected void addError(Model model, String key, String param, HttpServletRequest request) {
        List<String> errors = (List<String>) model.getAttribute("errors");
        if (errors == null) {
            errors = new ArrayList<>();
            model.addAttribute("errors", errors);
        }
        errors.add(getText(key, new Object[]{param}, request));
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
    @SuppressWarnings("unchecked")
    protected void addMessage(Model model, String key, HttpServletRequest request) {
        List<String> messages = (List<String>) model.getAttribute("messages");
        if (messages == null) {
            messages = new ArrayList<>();
            model.addAttribute("messages", messages);
        }
        messages.add(getText(key, request));
    }

    /**
     * Add a status message (resolved from the message source) with a parameter to the model.
     */
    @SuppressWarnings("unchecked")
    protected void addMessage(Model model, String key, String param, HttpServletRequest request) {
        List<String> messages = (List<String>) model.getAttribute("messages");
        if (messages == null) {
            messages = new ArrayList<>();
            model.addAttribute("messages", messages);
        }
        messages.add(getText(key, new Object[]{param}, request));
    }

    // --- Flash attribute helpers (for messages that survive redirects) ---

    /**
     * Add a flash message that survives a redirect.
     */
    @SuppressWarnings("unchecked")
    protected void addFlashMessage(RedirectAttributes redirectAttributes, String key, HttpServletRequest request) {
        List<String> messages = (List<String>) redirectAttributes.getFlashAttributes().get("messages");
        if (messages == null) {
            messages = new ArrayList<>();
        }
        messages.add(getText(key, request));
        redirectAttributes.addFlashAttribute("messages", messages);
    }

    /**
     * Add a flash message with a parameter that survives a redirect.
     */
    @SuppressWarnings("unchecked")
    protected void addFlashMessage(RedirectAttributes redirectAttributes, String key, String param, HttpServletRequest request) {
        List<String> messages = (List<String>) redirectAttributes.getFlashAttributes().get("messages");
        if (messages == null) {
            messages = new ArrayList<>();
        }
        messages.add(getText(key, new Object[]{param}, request));
        redirectAttributes.addFlashAttribute("messages", messages);
    }

    /**
     * Add a flash error that survives a redirect.
     */
    @SuppressWarnings("unchecked")
    protected void addFlashError(RedirectAttributes redirectAttributes, String key, HttpServletRequest request) {
        List<String> errors = (List<String>) redirectAttributes.getFlashAttributes().get("errors");
        if (errors == null) {
            errors = new ArrayList<>();
        }
        errors.add(getText(key, request));
        redirectAttributes.addFlashAttribute("errors", errors);
    }

    /**
     * Add a flash error with a parameter that survives a redirect.
     */
    @SuppressWarnings("unchecked")
    protected void addFlashError(RedirectAttributes redirectAttributes, String key, String param, HttpServletRequest request) {
        List<String> errors = (List<String>) redirectAttributes.getFlashAttributes().get("errors");
        if (errors == null) {
            errors = new ArrayList<>();
        }
        errors.add(getText(key, new Object[]{param}, request));
        redirectAttributes.addFlashAttribute("errors", errors);
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

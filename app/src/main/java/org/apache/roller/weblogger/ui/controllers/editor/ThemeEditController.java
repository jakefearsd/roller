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

package org.apache.roller.weblogger.ui.controllers.editor;

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.themes.SharedTheme;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.Theme;
import org.apache.roller.weblogger.pojos.ThemeTemplate;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Action for controlling theme selection.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class ThemeEditController extends BaseController {

    private static final Log log = LogFactory.getLog(ThemeEditController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "themeEdit";
    }

    @Override
    public String getPageTitle() {
        return "themeEditor.title";
    }

    @GetMapping("/themeEdit.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        loadThemeData(request, model);

        if (WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme())) {
            model.addAttribute("themeId", null);
            model.addAttribute("selectedThemeId", null);
            model.addAttribute("importTheme", false);
        } else {
            String themeId = getActionWeblog(request).getTheme().getId();
            model.addAttribute("themeId", themeId);
            model.addAttribute("selectedThemeId", themeId);
        }

        return ".ThemeEdit";
    }

    @PostMapping("/themeEdit!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @RequestParam(value = "themeType", required = false) String themeType,
                       @RequestParam(value = "selectedThemeId", required = false) String selectedThemeId,
                       @RequestParam(value = "importTheme", required = false, defaultValue = "false") boolean importTheme) {
        populateCommonModel(request, model);

        boolean sharedThemeCustomStylesheet = isSharedThemeCustomStylesheet(request);
        Weblog weblog = getActionWeblog(request);

        if (WeblogTheme.CUSTOM.equals(themeType)) {
            if (!customThemeAllowed(request)) {
                addError(model, "themeEditor.error.customThemeNotAllowed", request);
            } else if (importTheme) {
                try {
                    ThemeManager themeMgr = weblogger.getThemeManager();
                    if (!StringUtils.isEmpty(selectedThemeId)) {
                        SharedTheme t = themeMgr.getTheme(selectedThemeId);
                        boolean skipStylesheet = (sharedThemeCustomStylesheet
                                && selectedThemeId.equals(weblog.getEditorTheme()));
                        themeMgr.importTheme(getActionWeblog(request), t, skipStylesheet);
                        addMessage(model, "themeEditor.setCustomTheme.success", t.getName(), request);
                    }
                } catch (Exception re) {
                    log.error("Error customizing theme for weblog - " + weblog.getHandle(), re);
                    addError(model, "generic.error.check.logs", request);
                    loadThemeData(request, model);
                    return ".ThemeEdit";
                }
            }

            if (!hasErrors(model)) {
                try {
                    weblog.setEditorTheme(WeblogTheme.CUSTOM);
                    weblogger.getWeblogManager().saveWeblog(weblog);
                    weblogger.flush();
                    CacheManager.invalidate(weblog);
                    addMessage(model, "themeEditor.setTheme.success", WeblogTheme.CUSTOM, request);
                    addMessage(model, "themeEditor.setCustomTheme.instructions", request);
                } catch (WebloggerException re) {
                    log.error("Error saving weblog - " + weblog.getHandle(), re);
                    addError(model, "generic.error.check.logs", request);
                }
            }

        } else if ("shared".equals(themeType)) {
            Theme newTheme = null;
            try {
                ThemeManager themeMgr = weblogger.getThemeManager();
                newTheme = themeMgr.getTheme(selectedThemeId);
            } catch (Exception ex) {
                log.warn(ex);
                addError(model, "Theme not found", request);
            }

            if (!hasErrors(model)) {
                try {
                    String originalTheme = weblog.getEditorTheme();
                    WeblogManager mgr = weblogger.getWeblogManager();

                    if (!originalTheme.equals(selectedThemeId) && getActionWeblog(request).getTheme().getStylesheet() != null) {
                        WeblogTemplate stylesheet = mgr.getTemplateByAction(getActionWeblog(request), ComponentType.STYLESHEET);
                        if (stylesheet != null) {
                            mgr.removeTemplate(stylesheet);
                        }
                    }

                    weblog.setEditorTheme(selectedThemeId);
                    weblogger.getWeblogManager().saveWeblog(weblog);
                    weblogger.flush();
                    CacheManager.invalidate(weblog);

                    if (!originalTheme.equals(selectedThemeId)) {
                        addMessage(model, "themeEditor.setTheme.success", newTheme.getName(), request);
                    }
                } catch (WebloggerException re) {
                    log.error("Error saving weblog - " + weblog.getHandle(), re);
                    addError(model, "generic.error.check.logs", request);
                }
            }
        }

        loadThemeData(request, model);
        // Re-populate form
        if (WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme())) {
            model.addAttribute("themeId", null);
            model.addAttribute("selectedThemeId", null);
        } else {
            String themeId = getActionWeblog(request).getTheme().getId();
            model.addAttribute("themeId", themeId);
            model.addAttribute("selectedThemeId", themeId);
        }

        return ".ThemeEdit";
    }

    /**
     * Whether this weblog may adopt a custom theme.
     *
     * <p>{@code themes.customtheme.allowed} gates two of the Design tab's
     * three items in {@code editor-menu.xml} -- {@code stylesheetEdit} and
     * {@code templates}, which only matter once a weblog is customised --
     * and the customise link on the main menu; {@code themeEdit} itself
     * (picking among the shared themes) stays unconditionally reachable,
     * since that part is safe and reversible. But a hidden menu entry stops
     * nobody who posts to {@code themeEdit!save.rol} directly. The switch
     * imports the shared theme's templates and is one-way, so an
     * installation that has turned the option off needs the refusal
     * enforced here, not merely rendered.
     *
     * <p>A weblog that is <em>already</em> custom stays editable whatever the
     * setting says. Turning the option off is meant to stop new
     * customisations; since there is no way back from custom, refusing here
     * would strand any weblog customised while it was on.
     */
    private boolean customThemeAllowed(HttpServletRequest request) {
        return WebloggerRuntimeConfig.getBooleanProperty("themes.customtheme.allowed")
                || WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme());
    }

    /**
     * Themes not meant to be picked from this page: {@code frontpage} is the
     * {@code $site}-wide aggregator theme (its templates render the
     * multi-blog directory named by {@code site.frontpage.weblog.handle},
     * not an individual weblog -- see {@code SiteModel}/{@code
     * ThemeMatrixIT}'s exclusion of it for the same reason) and breaks a
     * normal weblog that adopts it. {@code theme.xml} has no theme-level
     * "hidden"/"sitewide" flag to key off of (only per-template {@code
     * <hidden>}), so this is a hardcoded id rather than metadata-driven;
     * revisit if that descriptor ever grows one.
     */
    private static final String SITEWIDE_ONLY_THEME_ID = "frontpage";

    private void loadThemeData(HttpServletRequest request, Model model) {
        ThemeManager themeMgr = weblogger.getThemeManager();
        String currentThemeId = WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme())
                ? null : getActionWeblog(request).getTheme().getId();
        List<SharedTheme> selectable = themeMgr.getEnabledThemesList().stream()
                .filter(t -> !SITEWIDE_ONLY_THEME_ID.equals(t.getId()) || SITEWIDE_ONLY_THEME_ID.equals(currentThemeId))
                .toList();
        model.addAttribute("themes", selectable);
        model.addAttribute("customTheme", WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme()));
        model.addAttribute("customThemeAllowed", customThemeAllowed(request));
        model.addAttribute("sharedThemeCustomStylesheet", isSharedThemeCustomStylesheet(request));

        // firstCustomization check
        try {
            model.addAttribute("firstCustomization",
                    weblogger.getWeblogManager()
                            .getTemplateByAction(getActionWeblog(request), ComponentType.WEBLOG) == null);
        } catch (WebloggerException ex) {
            log.error("Error looking up weblog template", ex);
        }
    }

    private boolean isSharedThemeCustomStylesheet(HttpServletRequest request) {
        try {
            if (!WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme())
                    && getActionWeblog(request).getTheme().getStylesheet() != null) {
                ThemeTemplate override = weblogger.getWeblogManager()
                        .getTemplateByLink(getActionWeblog(request),
                                getActionWeblog(request).getTheme().getStylesheet().getLink());
                if (override != null) {
                    return true;
                }
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up stylesheet on weblog - " + getActionWeblog(request).getHandle(), ex);
        }
        return false;
    }
}

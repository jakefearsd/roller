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

import java.util.Date;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.themes.SharedTheme;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.pojos.*;
import org.apache.roller.weblogger.pojos.TemplateRendition.RenditionType;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Action which handles editing for a weblog stylesheet override template.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class StylesheetEditController extends BaseController {

    private static final Log log = LogFactory.getLog(StylesheetEditController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "stylesheetEdit";
    }

    @Override
    public String getPageTitle() {
        return "stylesheetEdit.title";
    }

    @GetMapping("/stylesheetEdit.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        WeblogTemplate template = loadTemplate(request);
        model.addAttribute("template", template);
        model.addAttribute("sharedThemeStylesheet", isSharedThemeStylesheet(request));
        model.addAttribute("customTheme", isCustomTheme(request));
        model.addAttribute("sharedTheme", !isCustomTheme(request));

        if (template != null) {
            try {
                if (template.getTemplateRendition(RenditionType.STANDARD) != null) {
                    model.addAttribute("contentsStandard",
                            template.getTemplateRendition(RenditionType.STANDARD).getTemplate());
                } else {
                    model.addAttribute("contentsStandard", "");
                }
                if (template.getTemplateRendition(RenditionType.MOBILE) != null) {
                    model.addAttribute("contentsMobile",
                            template.getTemplateRendition(RenditionType.MOBILE).getTemplate());
                }
            } catch (WebloggerException e) {
                log.error("Error loading template renditions for stylesheet", e);
            }
        }
        return ".StylesheetEdit";
    }

    @PostMapping("/stylesheetEdit!copyStylesheet.rol")
    public String copyStylesheet(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        WeblogManager weblogManager = WebloggerFactory.getWeblogger().getWeblogManager();
        ThemeManager themeManager = WebloggerFactory.getWeblogger().getThemeManager();

        ThemeTemplate stylesheet = null;
        try {
            SharedTheme themeName = themeManager.getTheme(getActionWeblog(request).getEditorTheme());
            stylesheet = themeName.getStylesheet();
        } catch (WebloggerException ex) {
            // ignored
        }

        try {
            WeblogTemplate tmpl = weblogManager.getTemplateByLink(getActionWeblog(request), stylesheet.getLink());

            if (tmpl == null) {
                WeblogTemplate stylesheetTmpl = new WeblogTemplate();
                stylesheetTmpl.setWeblog(getActionWeblog(request));
                stylesheetTmpl.setAction(ComponentType.STYLESHEET);
                stylesheetTmpl.setName(stylesheet.getName());
                stylesheetTmpl.setDescription(stylesheet.getDescription());
                stylesheetTmpl.setLink(stylesheet.getLink());
                stylesheetTmpl.setHidden(false);
                stylesheetTmpl.setNavbar(false);
                stylesheetTmpl.setLastModified(new Date());

                TemplateRendition sCode = stylesheet.getTemplateRendition(RenditionType.STANDARD);
                if (sCode != null) {
                    CustomTemplateRendition standardRendition = new CustomTemplateRendition(
                            stylesheetTmpl, RenditionType.STANDARD);
                    standardRendition.setTemplate(sCode.getTemplate());
                    standardRendition.setTemplateLanguage(sCode.getTemplateLanguage());
                    weblogManager.saveTemplateRendition(standardRendition);
                }

                TemplateRendition mCode = stylesheet.getTemplateRendition(RenditionType.MOBILE);
                if (mCode != null) {
                    CustomTemplateRendition mobileRendition = new CustomTemplateRendition(
                            stylesheetTmpl, RenditionType.MOBILE);
                    mobileRendition.setTemplate(mCode.getTemplate());
                    mobileRendition.setTemplateLanguage(mCode.getTemplateLanguage());
                    weblogManager.saveTemplateRendition(mobileRendition);
                }

                weblogManager.saveTemplate(stylesheetTmpl);
                WebloggerFactory.getWeblogger().flush();
                addMessage(model, "stylesheetEdit.create.success", request);
            }
        } catch (WebloggerException ex) {
            log.error("Error finding/adding stylesheet template", ex);
            addError(model, "generic.error.check.logs", request);
        }

        // Revert to reload from shared theme
        return revert(request, model);
    }

    @PostMapping("/stylesheetEdit!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @RequestParam(value = "contentsStandard", required = false) String contentsStandard,
                       @RequestParam(value = "contentsMobile", required = false) String contentsMobile) {
        populateCommonModel(request, model);

        WeblogManager weblogManager = WebloggerFactory.getWeblogger().getWeblogManager();
        WeblogTemplate template = loadTemplate(request);

        if (!hasErrors(model) && template != null) {
            try {
                template.setLastModified(new Date());
                template.setAction(ComponentType.STYLESHEET);

                if (template.getTemplateRendition(RenditionType.STANDARD) != null) {
                    CustomTemplateRendition tc = template.getTemplateRendition(RenditionType.STANDARD);
                    tc.setTemplate(contentsStandard);
                    weblogManager.saveTemplateRendition(tc);
                } else {
                    CustomTemplateRendition tc = new CustomTemplateRendition(template, RenditionType.STANDARD);
                    tc.setTemplate("");
                    weblogManager.saveTemplateRendition(tc);
                }

                if (template.getTemplateRendition(RenditionType.MOBILE) != null) {
                    CustomTemplateRendition tc = template.getTemplateRendition(RenditionType.MOBILE);
                    tc.setTemplate(contentsMobile);
                    weblogManager.saveTemplateRendition(tc);
                }

                weblogManager.saveTemplate(template);
                WebloggerFactory.getWeblogger().flush();
                CacheManager.invalidate(template);
                addMessage(model, "stylesheetEdit.save.success", template.getName(), request);

            } catch (WebloggerException ex) {
                log.error("Error updating stylesheet template", ex);
                addError(model, "Error saving template - check Roller logs", request);
            }
        }

        model.addAttribute("template", template);
        model.addAttribute("contentsStandard", contentsStandard);
        model.addAttribute("contentsMobile", contentsMobile);
        model.addAttribute("sharedThemeStylesheet", isSharedThemeStylesheet(request));
        model.addAttribute("customTheme", isCustomTheme(request));
        model.addAttribute("sharedTheme", !isCustomTheme(request));
        return ".StylesheetEdit";
    }

    @PostMapping("/stylesheetEdit!revert.rol")
    public String revert(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        if (!isCustomTheme(request) && !hasErrors(model)) {
            WeblogTemplate template = loadTemplate(request);
            try {
                WeblogManager weblogManager = WebloggerFactory.getWeblogger().getWeblogManager();
                ThemeManager tmgr = WebloggerFactory.getWeblogger().getThemeManager();
                Theme theme = tmgr.getTheme(getActionWeblog(request).getEditorTheme());

                template.setLastModified(new Date());

                if (template.getTemplateRendition(RenditionType.STANDARD) != null) {
                    TemplateRendition templateCode = theme.getStylesheet().getTemplateRendition(RenditionType.STANDARD);
                    CustomTemplateRendition existingTemplateCode = template.getTemplateRendition(RenditionType.STANDARD);
                    existingTemplateCode.setTemplate(templateCode.getTemplate());
                    weblogManager.saveTemplateRendition(existingTemplateCode);
                }
                if (template.getTemplateRendition(RenditionType.MOBILE) != null) {
                    TemplateRendition templateCode = theme.getStylesheet().getTemplateRendition(RenditionType.MOBILE);
                    CustomTemplateRendition existingTemplateCode = template.getTemplateRendition(RenditionType.MOBILE);
                    existingTemplateCode.setTemplate(templateCode.getTemplate());
                }

                weblogManager.saveTemplate(template);
                WebloggerFactory.getWeblogger().flush();
                CacheManager.invalidate(template);
                addMessage(model, "stylesheetEdit.revert.success", template.getName(), request);

            } catch (WebloggerException ex) {
                log.error("Error updating stylesheet template", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        return execute(request, model);
    }

    @PostMapping("/stylesheetEdit!delete.rol")
    public String delete(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        WeblogTemplate template = loadTemplate(request);
        if (template != null && !isCustomTheme(request) && !hasErrors(model)) {
            try {
                WeblogManager weblogManager = WebloggerFactory.getWeblogger().getWeblogManager();
                weblogManager.removeTemplate(template);
                weblogManager.saveWeblog(getActionWeblog(request));
                CacheManager.invalidate(template);
                WebloggerFactory.getWeblogger().flush();
                addMessage(model, "stylesheetEdit.default.success", template.getName(), request);
                template = null;
            } catch (Exception e) {
                log.error("Error deleting stylesheet template", e);
                addError(model, "generic.error.check.logs", request);
            }
        }

        model.addAttribute("template", template);
        model.addAttribute("sharedThemeStylesheet", isSharedThemeStylesheet(request));
        model.addAttribute("customTheme", isCustomTheme(request));
        model.addAttribute("sharedTheme", !isCustomTheme(request));
        return ".StylesheetEdit";
    }

    private WeblogTemplate loadTemplate(HttpServletRequest request) {
        try {
            ThemeTemplate stylesheet;
            if (!isCustomTheme(request)) {
                ThemeManager themeManager = WebloggerFactory.getWeblogger().getThemeManager();
                SharedTheme themeName = themeManager.getTheme(getActionWeblog(request).getEditorTheme());
                stylesheet = themeName.getStylesheet();
            } else {
                stylesheet = getActionWeblog(request).getTheme().getStylesheet();
            }
            if (stylesheet != null) {
                return WebloggerFactory.getWeblogger().getWeblogManager()
                        .getTemplateByLink(getActionWeblog(request), stylesheet.getLink());
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up stylesheet on weblog - " + getActionWeblog(request).getHandle(), ex);
        }
        return null;
    }

    private boolean isCustomTheme(HttpServletRequest request) {
        return WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme());
    }

    private boolean isSharedThemeStylesheet(HttpServletRequest request) {
        try {
            if (!isCustomTheme(request)) {
                ThemeManager themeManager = WebloggerFactory.getWeblogger().getThemeManager();
                SharedTheme themeName = themeManager.getTheme(getActionWeblog(request).getEditorTheme());
                return themeName.getStylesheet() != null;
            }
        } catch (WebloggerException ex) {
            log.error("Error checking shared theme stylesheet", ex);
        }
        return false;
    }
}

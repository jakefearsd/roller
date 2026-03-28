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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.*;
import org.apache.roller.weblogger.pojos.TemplateRendition.RenditionType;
import org.apache.roller.weblogger.pojos.TemplateRendition.TemplateLanguage;
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
 * Templates listing page.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class TemplatesController extends BaseController {

    private static final Log log = LogFactory.getLog(TemplatesController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "templates";
    }

    @Override
    public String getPageTitle() {
        return "pagesForm.title";
    }

    @GetMapping("/templates.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        loadTemplatesList(request, model);
        return ".Templates";
    }

    @PostMapping("/templates!add.rol")
    public String add(HttpServletRequest request, Model model,
                      @RequestParam(value = "newTmplName", required = false) String newTmplName,
                      @RequestParam(value = "newTmplAction", required = false) ComponentType newTmplAction) {
        populateCommonModel(request, model);

        myValidate(newTmplName, newTmplAction, request, model);

        if (!hasErrors(model)) {
            try {
                WeblogTemplate newTemplate = new WeblogTemplate();
                newTemplate.setWeblog(getActionWeblog(request));
                newTemplate.setAction(newTmplAction);
                newTemplate.setName(newTmplName);
                newTemplate.setHidden(false);
                newTemplate.setNavbar(false);
                newTemplate.setLastModified(new Date());

                if (ComponentType.CUSTOM.equals(newTmplAction)) {
                    newTemplate.setLink(newTmplName);
                }

                if (ComponentType.WEBLOG.equals(newTmplAction)) {
                    newTemplate.setName(WeblogTemplate.DEFAULT_PAGE);
                }

                WebloggerFactory.getWeblogger().getWeblogManager().saveTemplate(newTemplate);

                CustomTemplateRendition standardRendition = new CustomTemplateRendition(newTemplate, RenditionType.STANDARD);
                standardRendition.setTemplate(getText("pageForm.newTemplateContent", request));
                standardRendition.setTemplateLanguage(TemplateLanguage.VELOCITY);
                WebloggerFactory.getWeblogger().getWeblogManager().saveTemplateRendition(standardRendition);

                if (WeblogTemplate.DEFAULT_PAGE.equals(newTemplate.getName())) {
                    WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(getActionWeblog(request));
                }

                WebloggerFactory.getWeblogger().flush();

            } catch (WebloggerException ex) {
                log.error("Error adding new template for weblog - " + getActionWeblog(request).getHandle(), ex);
                addError(model, "Error adding new template - check Roller logs", request);
            }
        }

        loadTemplatesList(request, model);
        return ".Templates";
    }

    @PostMapping("/templates!remove.rol")
    public String remove(HttpServletRequest request, Model model,
                         @RequestParam(value = "removeId", required = false) String removeId) {
        populateCommonModel(request, model);

        WeblogTemplate template = null;
        try {
            template = WebloggerFactory.getWeblogger().getWeblogManager().getTemplate(removeId);
        } catch (WebloggerException e) {
            addError(model, "Error deleting template - check Roller logs", request);
        }

        if (template != null) {
            try {
                if (!template.isRequired()
                        || !WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme())) {

                    WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();

                    if (template.getName().equals(WeblogTemplate.DEFAULT_PAGE)) {
                        ThemeTemplate stylesheet = getActionWeblog(request).getTheme().getStylesheet();
                        if (stylesheet != null && getActionWeblog(request).getTheme().getStylesheet() != null
                                && stylesheet.getLink().equals(
                                getActionWeblog(request).getTheme().getStylesheet().getLink())) {
                            WeblogTemplate css = mgr.getTemplateByLink(getActionWeblog(request), stylesheet.getLink());
                            if (css != null) {
                                mgr.removeTemplate(css);
                            }
                        }
                    }

                    CacheManager.invalidate(template);
                    mgr.removeTemplate(template);
                    WebloggerFactory.getWeblogger().flush();
                } else {
                    addError(model, "editPages.remove.requiredTemplate", request);
                }
            } catch (Exception ex) {
                log.error("Error removing page - " + removeId, ex);
                addError(model, "editPages.remove.error", request);
            }
        } else {
            addError(model, "editPages.remove.error", request);
        }

        loadTemplatesList(request, model);
        return ".Templates";
    }

    private void loadTemplatesList(HttpServletRequest request, Model model) {
        try {
            List<WeblogTemplate> raw = WebloggerFactory.getWeblogger()
                    .getWeblogManager().getTemplates(getActionWeblog(request));
            List<WeblogTemplate> pages = new ArrayList<>(raw);

            if (getActionWeblog(request).getTheme().getStylesheet() != null) {
                pages.remove(WebloggerFactory.getWeblogger().getWeblogManager()
                        .getTemplateByLink(getActionWeblog(request),
                                getActionWeblog(request).getTheme().getStylesheet().getLink()));
            }
            model.addAttribute("templates", pages);

            Map<ComponentType, String> actionsMap = new EnumMap<>(ComponentType.class);
            actionsMap.put(ComponentType.CUSTOM, ComponentType.CUSTOM.getReadableName());

            if (WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme())) {
                actionsMap.put(ComponentType.PERMALINK, ComponentType.PERMALINK.getReadableName());
                actionsMap.put(ComponentType.SEARCH, ComponentType.SEARCH.getReadableName());
                actionsMap.put(ComponentType.WEBLOG, ComponentType.WEBLOG.getReadableName());
                actionsMap.put(ComponentType.TAGSINDEX, ComponentType.TAGSINDEX.getReadableName());

                for (WeblogTemplate tmpPage : pages) {
                    if (!ComponentType.CUSTOM.equals(tmpPage.getAction())) {
                        actionsMap.remove(tmpPage.getAction());
                    }
                }
            } else {
                actionsMap.put(ComponentType.WEBLOG, ComponentType.WEBLOG.getReadableName());
                for (WeblogTemplate tmpPage : pages) {
                    if (ComponentType.WEBLOG.equals(tmpPage.getAction())) {
                        actionsMap.remove(ComponentType.WEBLOG);
                        break;
                    }
                }
            }
            model.addAttribute("availableActions", actionsMap);
            model.addAttribute("customTheme", WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme()));

        } catch (WebloggerException ex) {
            log.error("Error getting templates for weblog - " + getActionWeblog(request).getHandle(), ex);
            addError(model, "Error getting template list - check Roller logs", request);
        }
    }

    private void myValidate(String newTmplName, ComponentType newTmplAction,
                            HttpServletRequest request, Model model) {
        if (StringUtils.isEmpty(newTmplName)) {
            addError(model, "Template.error.nameNull", request);
        } else if (newTmplName.length() > RollerConstants.TEXTWIDTH_255) {
            addError(model, "Template.error.nameSize", request);
        }

        if (newTmplAction == null) {
            addError(model, "Template.error.actionNull", request);
        }

        try {
            WeblogTemplate existingPage = WebloggerFactory.getWeblogger().getWeblogManager()
                    .getTemplateByName(getActionWeblog(request), newTmplName);
            if (existingPage != null) {
                addError(model, "pagesForm.error.alreadyExists", newTmplName, request);
            }
        } catch (WebloggerException ex) {
            log.error("Error checking for existing template", ex);
        }
    }
}

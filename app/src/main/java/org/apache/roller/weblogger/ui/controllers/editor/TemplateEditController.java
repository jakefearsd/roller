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
import java.util.EnumMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.TemplateRendition.TemplateLanguage;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Action which handles editing for a single WeblogTemplate.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class TemplateEditController extends BaseController {

    private static final Log log = LogFactory.getLog(TemplateEditController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "templateEdit";
    }

    @Override
    public String getPageTitle() {
        return "pagesForm.title";
    }

    @GetMapping("/templateEdit.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") TemplateEditBean bean) {
        populateCommonModel(request, model);
        addTemplateLanguages(model);

        WeblogTemplate template = lookupTemplate(bean.getId());
        if (template == null) {
            addError(model, "Unable to locate specified template", request);
            return ".Templates";
        }

        try {
            bean.copyFrom(template);
            model.addAttribute("template", template);

            if (StringUtils.isEmpty(template.getOutputContentType())) {
                bean.setAutoContentType(Boolean.TRUE);
            } else {
                bean.setAutoContentType(Boolean.FALSE);
                bean.setManualContentType(template.getOutputContentType());
            }
        } catch (WebloggerException ex) {
            log.error("Error updating page - " + bean.getId(), ex);
            addError(model, "Error saving template - check Roller logs", request);
        }

        return ".TemplateEdit";
    }

    @PostMapping("/templateEdit!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") TemplateEditBean bean) {
        populateCommonModel(request, model);
        addTemplateLanguages(model);

        WeblogTemplate template = lookupTemplate(bean.getId());
        if (template == null) {
            addError(model, "Unable to locate specified template", request);
            return ".Templates";
        }

        model.addAttribute("template", template);

        myValidate(bean, template, request, model);

        if (!hasErrors(model)) {
            try {
                bean.copyTo(template);
                template.setLastModified(new Date());

                if (bean.getAutoContentType() == null || !bean.getAutoContentType()) {
                    template.setOutputContentType(bean.getManualContentType());
                } else {
                    template.setOutputContentType(null);
                }

                WebloggerFactory.getWeblogger().getWeblogManager().saveTemplate(template);
                WebloggerFactory.getWeblogger().flush();
                CacheManager.invalidate(template);

                addMessage(model, "pageForm.save.success", template.getName(), request);

            } catch (Exception ex) {
                log.error("Error updating page - " + bean.getId(), ex);
                addError(model, "Error updating template - check Roller logs", request);
            }
        }

        return ".TemplateEdit";
    }

    private void myValidate(TemplateEditBean bean, WeblogTemplate template,
                            HttpServletRequest request, Model model) {
        if (!template.getName().equals(bean.getName())) {
            try {
                if (WebloggerFactory.getWeblogger().getWeblogManager()
                        .getTemplateByName(getActionWeblog(request), bean.getName()) != null) {
                    addError(model, "pagesForm.error.alreadyExists", bean.getName(), request);
                }
            } catch (WebloggerException ex) {
                log.error("Error checking page name uniqueness", ex);
            }
        }

        if (!StringUtils.isEmpty(bean.getLink()) && !bean.getLink().equals(template.getLink())) {
            try {
                if (WebloggerFactory.getWeblogger().getWeblogManager()
                        .getTemplateByLink(getActionWeblog(request), bean.getLink()) != null) {
                    addError(model, "pagesForm.error.alreadyExists", bean.getLink(), request);
                }
            } catch (WebloggerException ex) {
                log.error("Error checking page link uniqueness", ex);
            }
        }
    }

    private WeblogTemplate lookupTemplate(String id) {
        if (id == null) {
            return null;
        }
        try {
            return WebloggerFactory.getWeblogger().getWeblogManager().getTemplate(id);
        } catch (WebloggerException ex) {
            log.error("Error looking up template - " + id, ex);
        }
        return null;
    }

    private void addTemplateLanguages(Model model) {
        Map<TemplateLanguage, String> langMap = new EnumMap<>(TemplateLanguage.class);
        for (TemplateLanguage lang : TemplateLanguage.values()) {
            langMap.put(lang, lang.getReadableName());
        }
        model.addAttribute("templateLanguages", langMap);
    }

    @ModelAttribute("bean")
    public TemplateEditBean getBean() {
        return new TemplateEditBean();
    }
}

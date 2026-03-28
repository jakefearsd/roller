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

package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.plugins.PluginManager;
import org.apache.roller.weblogger.business.plugins.comment.WeblogEntryCommentPlugin;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.config.runtime.ConfigDef;
import org.apache.roller.weblogger.config.runtime.PropertyDef;
import org.apache.roller.weblogger.config.runtime.RuntimeConfigDefs;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * Action which handles editing of global configuration.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class GlobalConfigController extends BaseController {

    private static final Log log = LogFactory.getLog(GlobalConfigController.class);

    private final ResourceBundle bundle = ResourceBundle.getBundle("ApplicationResources");

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getPageTitle() {
        return "configForm.title";
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "globalConfig";
    }

    /**
     * Display global properties editor form.
     */
    @GetMapping("/globalConfig.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        Map<String, RuntimeConfigProperty> properties = loadProperties(model);
        ConfigDef globalConfigDef = loadConfigDef();
        Collection<Weblog> weblogs = loadWeblogs(model);
        List<WeblogEntryCommentPlugin> pluginsList = loadPlugins();

        model.addAttribute("properties", properties);
        model.addAttribute("globalConfigDef", globalConfigDef);
        model.addAttribute("weblogs", weblogs);
        model.addAttribute("pluginsList", pluginsList);

        // setup array of configured plugins
        String[] commentPlugins = new String[0];
        if (!StringUtils.isEmpty(WebloggerRuntimeConfig.getProperty("users.comments.plugins"))) {
            commentPlugins = StringUtils.split(
                    WebloggerRuntimeConfig.getProperty("users.comments.plugins"), ",");
        }
        model.addAttribute("commentPlugins", commentPlugins);

        return ".GlobalConfig";
    }

    /**
     * Save global properties.
     */
    @PostMapping("/globalConfig!save.rol")
    public String save(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        Map<String, RuntimeConfigProperty> properties = loadProperties(model);
        ConfigDef globalConfigDef = loadConfigDef();
        Collection<Weblog> weblogs = loadWeblogs(model);
        List<WeblogEntryCommentPlugin> pluginsList = loadPlugins();

        model.addAttribute("properties", properties);
        model.addAttribute("globalConfigDef", globalConfigDef);
        model.addAttribute("weblogs", weblogs);
        model.addAttribute("pluginsList", pluginsList);

        // get comment plugins from request
        String[] commentPlugins = request.getParameterValues("commentPlugins");
        if (commentPlugins == null) {
            commentPlugins = new String[0];
        }
        model.addAttribute("commentPlugins", commentPlugins);

        // only set values for properties that are already defined
        RuntimeConfigProperty updProp;
        String incomingProp;
        for (String propName : properties.keySet()) {
            updProp = properties.get(propName);
            incomingProp = request.getParameter(updProp.getName());

            PropertyDef propertyDef = globalConfigDef.getPropertyDef(propName);
            if (propertyDef == null) {
                // we're only processing defined properties, i.e. ones shown in the UI
                continue;
            }

            if (propertyDef.getType().equals("boolean")) {
                try {
                    if (incomingProp == null) {
                        updProp.setValue("false");
                    } else {
                        boolean value = Boolean.parseBoolean(incomingProp);
                        updProp.setValue(Boolean.toString(value));
                    }
                    log.debug("Set boolean " + propName + " = " + incomingProp);
                } catch (Exception nfe) {
                    String propDesc = bundle.getString(propertyDef.getKey());
                    addError(model, "ConfigForm.invalidBooleanProperty", propDesc, request);
                }

            } else if (incomingProp != null && propertyDef.getType().equals("integer")) {
                try {
                    Integer.parseInt(incomingProp);
                    updProp.setValue(incomingProp);
                    log.debug("Set integer " + propName + " = " + incomingProp);
                } catch (NumberFormatException nfe) {
                    String propDesc = bundle.getString(propertyDef.getKey());
                    addError(model, "ConfigForm.invalidIntegerProperty", propDesc, request);
                }

            } else if (incomingProp != null && propertyDef.getType().equals("float")) {
                try {
                    Float.parseFloat(incomingProp);
                    updProp.setValue(incomingProp);
                    log.debug("Set float " + propName + " = " + incomingProp);
                } catch (NumberFormatException nfe) {
                    String propDesc = bundle.getString(propertyDef.getKey());
                    addError(model, "ConfigForm.invalidFloatProperty", propDesc, request);
                }

            } else if (incomingProp != null) {
                updProp.setValue(incomingProp.trim());
                log.debug("Set something " + propName + " = " + incomingProp);

            } else if (propertyDef.getName().equals("users.comments.plugins")) {
                // not a problem

            } else {
                addError(model, "ConfigForm.invalidProperty", propName, request);
            }
        }

        if (hasErrors(model)) {
            return ".GlobalConfig";
        }

        // special handling for comment plugins
        String enabledPlugins = "";
        if (commentPlugins.length > 0) {
            enabledPlugins = StringUtils.join(commentPlugins, ",");
        }
        RuntimeConfigProperty prop = properties.get("users.comments.plugins");
        prop.setValue(enabledPlugins);

        try {
            // save 'em and flush
            PropertiesManager mgr = WebloggerFactory.getWeblogger().getPropertiesManager();
            mgr.saveProperties(properties);
            WebloggerFactory.getWeblogger().flush();

            // notify user of our success
            addMessage(model, "generic.changes.saved", request);

        } catch (WebloggerException ex) {
            log.error("Error saving roller properties", ex);
            addError(model, "generic.error.check.logs", request);
        }

        return ".GlobalConfig";
    }

    private Map<String, RuntimeConfigProperty> loadProperties(Model model) {
        Map<String, RuntimeConfigProperty> properties = Collections.emptyMap();
        try {
            PropertiesManager mgr = WebloggerFactory.getWeblogger().getPropertiesManager();
            properties = mgr.getProperties();
        } catch (WebloggerException ex) {
            log.error("Error getting runtime properties map", ex);
        }
        return properties;
    }

    private ConfigDef loadConfigDef() {
        RuntimeConfigDefs defs = WebloggerRuntimeConfig.getRuntimeConfigDefs();
        List<ConfigDef> configDefs = defs.getConfigDefs();
        for (ConfigDef configDef : configDefs) {
            if ("global-properties".equals(configDef.getName())) {
                return configDef;
            }
        }
        return null;
    }

    private Collection<Weblog> loadWeblogs(Model model) {
        try {
            WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();
            return mgr.getWeblogs(true, null, null, null, 0, -1);
        } catch (WebloggerException ex) {
            log.error("Error getting weblogs", ex);
            return Collections.emptyList();
        }
    }

    private List<WeblogEntryCommentPlugin> loadPlugins() {
        PluginManager pmgr = WebloggerFactory.getWeblogger().getPluginManager();
        return pmgr.getCommentPlugins();
    }
}

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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.RollerException;
import org.apache.roller.planet.config.PlanetRuntimeConfig;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.config.runtime.ConfigDef;
import org.apache.roller.weblogger.config.runtime.DisplayGroup;
import org.apache.roller.weblogger.config.runtime.PropertyDef;
import org.apache.roller.weblogger.config.runtime.RuntimeConfigDefs;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * Planet Config Action.
 *
 * Handles editing of planet global runtime properties.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class PlanetConfigController extends BaseController {

    private static final Log log = LogFactory.getLog(PlanetConfigController.class);

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
        return "planetConfig.title";
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "planetConfig";
    }

    @GetMapping("/planetConfig.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        Map<String, RuntimeConfigProperty> properties = loadProperties();
        ConfigDef globalConfigDef = loadConfigDef();

        model.addAttribute("properties", properties);
        model.addAttribute("globalConfigDef", globalConfigDef);

        return ".PlanetConfig";
    }

    @PostMapping("/planetConfig!save.rol")
    public String save(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        Map<String, RuntimeConfigProperty> properties = loadProperties();
        ConfigDef globalConfigDef = loadConfigDef();

        model.addAttribute("properties", properties);
        model.addAttribute("globalConfigDef", globalConfigDef);

        try {
            String incomingProp;

            // only set values for properties that are already defined
            RuntimeConfigDefs defs = PlanetRuntimeConfig.getRuntimeConfigDefs();
            for (ConfigDef configDef : defs.getConfigDefs()) {
                for (DisplayGroup displayGroup : configDef.getDisplayGroups()) {
                    for (PropertyDef propertyDef : displayGroup.getPropertyDefs()) {

                        String propName = propertyDef.getName();
                        log.debug("Checking property [" + propName + "]");

                        RuntimeConfigProperty updProp = properties.get(propName);
                        if (updProp == null) {
                            updProp = new RuntimeConfigProperty(propName, "");
                            properties.put(propName, updProp);
                        }

                        incomingProp = request.getParameter(updProp.getName());

                        // some special treatment for booleans
                        if (updProp.getValue() != null && (updProp.getValue().equals("true")
                                || updProp.getValue().equals("false"))) {
                            incomingProp = (incomingProp == null
                                    || !incomingProp.equals("on")) ? "false" : "true";
                        }

                        // only work on props that were submitted with the request
                        if (incomingProp != null) {
                            log.debug("Setting new value for [" + propName + "]");
                            updProp.setValue(incomingProp.trim());
                        }
                    }
                }
            }

            // save it
            PropertiesManager pMgr = WebloggerFactory.getWeblogger().getPropertiesManager();
            pMgr.saveProperties(properties);
            WebloggerFactory.getWeblogger().flush();

            addMessage(model, "ConfigForm.message.saveSucceeded", request);

        } catch (RollerException e) {
            log.error(e);
            addError(model, "ConfigForm.error.saveFailed", request);
        }

        return ".PlanetConfig";
    }

    private Map<String, RuntimeConfigProperty> loadProperties() {
        try {
            PropertiesManager pMgr = WebloggerFactory.getWeblogger().getPropertiesManager();
            return pMgr.getProperties();
        } catch (RollerException ex) {
            log.error("Error loading planet properties");
            return Collections.emptyMap();
        }
    }

    private ConfigDef loadConfigDef() {
        RuntimeConfigDefs defs = PlanetRuntimeConfig.getRuntimeConfigDefs();
        List<ConfigDef> configDefs = defs.getConfigDefs();
        for (ConfigDef configDef : configDefs) {
            if ("global-properties".equals(configDef.getName())) {
                return configDef;
            }
        }
        return null;
    }
}

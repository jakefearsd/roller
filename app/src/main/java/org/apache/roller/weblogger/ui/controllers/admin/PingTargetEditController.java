/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.pings.PingTargetManager;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.PingTarget;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * Add or modify a common ping target.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class PingTargetEditController extends BaseController {

    private static final Log log = LogFactory.getLog(PingTargetEditController.class);

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "commonPingTargets";
    }

    /**
     * Save a new ping target (add).
     */
    @PostMapping("/commonPingTargetAdd.rol")
    public String addSave(HttpServletRequest request, Model model,
                          PingTargetBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("bean", bean);

        PingTarget pingTarget = new PingTarget();
        pingTarget.setConditionCode(PingTarget.CONDITION_OK);
        pingTarget.setAutoEnabled(false);

        myValidate(bean, pingTarget, true, model, request);

        if (!hasErrors(model)) {
            try {
                bean.copyTo(pingTarget);
                PingTargetManager pingTargetMgr = WebloggerFactory.getWeblogger().getPingTargetManager();
                pingTargetMgr.savePingTarget(pingTarget);
                WebloggerFactory.getWeblogger().flush();

                addMessage(model, "pingTarget.created", pingTarget.getName(), request);
                return "redirect:/roller-ui/admin/commonPingTargets.rol";
            } catch (WebloggerException ex) {
                log.error("Error adding ping target", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        return ".PingTargetEdit";
    }

    /**
     * Save an existing ping target (edit).
     */
    @PostMapping("/commonPingTargetEdit.rol")
    public String editSave(HttpServletRequest request, Model model,
                           PingTargetBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("bean", bean);

        PingTarget pingTarget = null;
        if (!StringUtils.isEmpty(bean.getId())) {
            try {
                PingTargetManager pingTargetMgr = WebloggerFactory.getWeblogger().getPingTargetManager();
                pingTarget = pingTargetMgr.getPingTarget(bean.getId());
            } catch (WebloggerException ex) {
                log.error("Error looking up ping target - " + bean.getId());
            }
        }

        if (pingTarget == null) {
            addError(model, "pingTarget.notFound", request);
            return ".PingTargetEdit";
        }

        myValidate(bean, pingTarget, false, model, request);

        if (!hasErrors(model)) {
            try {
                bean.copyTo(pingTarget);
                PingTargetManager pingTargetMgr = WebloggerFactory.getWeblogger().getPingTargetManager();
                pingTargetMgr.savePingTarget(pingTarget);
                WebloggerFactory.getWeblogger().flush();

                addMessage(model, "pingTarget.updated", pingTarget.getName(), request);
                return "redirect:/roller-ui/admin/commonPingTargets.rol";
            } catch (WebloggerException ex) {
                log.error("Error editing ping target", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        return ".PingTargetEdit";
    }

    private void myValidate(PingTargetBean bean, PingTarget pingTarget,
                            boolean isAdd, Model model, HttpServletRequest request) {
        try {
            PingTargetManager pingTargetMgr = WebloggerFactory.getWeblogger().getPingTargetManager();
            if (StringUtils.isEmpty(bean.getName())) {
                addError(model, "pingTarget.nameMissing", request);
            } else {
                if (isAdd || !pingTarget.getName().equals(bean.getName())) {
                    if (pingTargetMgr.targetNameExists(bean.getName())) {
                        addError(model, "pingTarget.nameNotUnique", request);
                    }
                }
            }
            if (StringUtils.isEmpty(bean.getPingUrl())) {
                addError(model, "pingTarget.pingUrlMissing", request);
            } else {
                if (!pingTargetMgr.isUrlWellFormed(bean.getPingUrl())) {
                    addError(model, "pingTarget.malformedUrl", request);
                } else if (!pingTargetMgr.isHostnameKnown(bean.getPingUrl())) {
                    addError(model, "pingTarget.unknownHost", request);
                }
            }
        } catch (WebloggerException ex) {
            log.error("Error validating ping target", ex);
            addError(model, "generic.error.check.logs", request);
        }
    }
}

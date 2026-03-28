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

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;


/**
 * Action for displaying rendering cache info.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class CacheInfoController extends BaseController {

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
        return "cacheInfo.title";
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "cacheInfo";
    }

    @GetMapping("/cacheInfo.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        Map<String, Map<String, Object>> stats = CacheManager.getStats();
        model.addAttribute("stats", stats);

        return ".CacheInfo";
    }

    /**
     * Clear one or all of the caches.
     */
    @PostMapping("/cacheInfo!clear.rol")
    public String clear(HttpServletRequest request, Model model,
                        @RequestParam(value = "cache", required = false) String cache) {
        populateCommonModel(request, model);

        // see if a specific cache was specified
        if (cache != null && !cache.isEmpty()) {
            CacheManager.clear(cache);
        } else {
            CacheManager.clear();
        }

        // update stats after clear
        Map<String, Map<String, Object>> stats = CacheManager.getStats();
        model.addAttribute("stats", stats);

        return ".CacheInfo";
    }
}

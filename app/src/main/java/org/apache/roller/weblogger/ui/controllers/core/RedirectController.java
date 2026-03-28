package org.apache.roller.weblogger.ui.controllers.core;

import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Collections;
import java.util.List;

/**
 * Handles simple redirect actions that had no Struts action class —
 * they were just result mappings to JSP pages.
 */
@Controller
@RequestMapping("/roller-ui")
public class RedirectController extends BaseController {

    @Override
    public boolean isUserRequired() { return false; }

    @Override
    public boolean isWeblogRequired() { return false; }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return Collections.emptyList();
    }

    @GetMapping("/login-redirect.rol")
    public String loginRedirect() {
        return "forward:/roller-ui/login-redirect.jsp";
    }

    @GetMapping("/home.rol")
    public String home() {
        return "redirect:/";
    }

    @GetMapping("/logout.rol")
    public String logout() {
        return "forward:/roller-ui/logout-redirect.jsp";
    }
}

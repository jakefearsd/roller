package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.mail.MessagingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang3.CharSetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.config.runtime.ConfigDef;
import org.apache.roller.weblogger.config.runtime.DisplayGroup;
import org.apache.roller.weblogger.config.runtime.PropertyDef;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.UserToken;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.controllers.core.PasswordLinkMailer;
import org.apache.roller.weblogger.ui.controllers.util.UIUtils;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.auth.AdminScoped;
import org.apache.roller.weblogger.ui.restapi.dto.AdminDtos;
import org.apache.roller.weblogger.util.TokenGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site-wide user administration and runtime configuration -- {@code
 * UISecurityEnforced} declares {@code GlobalPermission.ADMIN}, the same
 * requirement {@code UserAdminController}/{@code UserEditController}/{@code
 * GlobalConfigController} enforce for the JSP admin screens; {@code
 * RollerHandlerInterceptor} is what actually enforces it, and this
 * controller adds no permission checking of its own. Never weblog-scoped
 * ({@code isWeblogRequired() == false}) -- these actions have no per-weblog
 * meaning at all.
 *
 * <p><b>{@code POST /users} never accepts a password.</b> The account is
 * created disabled with a random, never-disclosed password, and a {@code
 * PASSWORD_SET} token is issued and emailed through the same {@link
 * PasswordLinkMailer#sendLink} path {@code UserEditController}'s "send
 * set-password link" admin action uses -- no plaintext password ever
 * crosses this API. Mail must already be configured
 * ({@link PasswordLinkMailer#isReady()}) or the request is refused before
 * any account is created, matching {@code UserEditController}'s own rule
 * that a blank password is only safe to accept when a link can actually be
 * delivered.
 */
@RestController
@RequestMapping("/v1/admin")
@AdminScoped
public class AdminApi extends BaseApiController implements UISecurityEnforced {

    private static final int MAX_LIMIT = 200;

    /**
     * Deliberately permissive (no TLD/domain checking, just "something at
     * something with a dot") -- rejecting only the inputs that would
     * otherwise reach {@code MailUtil.sendTextMessage} and fail there in a
     * way this API could only report as an opaque 500.
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    // ------------------------------------------------------------- users

    @GetMapping("/users")
    public List<AdminDtos.UserView> listUsers(
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {
        // Same trap as EntriesApi.list: a negative offset reaches JPA's
        // setFirstResult and throws; UserManager.getUsers treats length==-1
        // as NO LIMIT (JPAUserManagerImpl only calls setMaxResults when
        // length != -1), so limit=-1 would silently run an unbounded query
        // instead of failing.
        if (limit < 1 || offset < 0) {
            throw ApiException.badRequest("limit must be at least 1 and offset must not be negative.");
        }
        int boundedLimit = Math.min(limit, MAX_LIMIT);

        UserManager mgr = weblogger.getUserManager();
        List<AdminDtos.UserView> views = new ArrayList<>();
        for (User user : mgr.getUsers(enabled, null, null, offset, boundedLimit)) {
            views.add(AdminDtos.toView(user, roles(user)));
        }
        return views;
    }

    @PostMapping("/users")
    public ResponseEntity<AdminDtos.UserView> createUser(@RequestBody AdminDtos.UserCreate body)
            throws WebloggerException {
        String userName = body.userName() == null ? null : body.userName().trim();
        if (StringUtils.isBlank(userName)) {
            throw ApiException.badRequest("userName is required.");
        }
        String allowed = WebloggerConfig.getProperty("username.allowedChars");
        if (StringUtils.isBlank(allowed)) {
            allowed = UIUtils.DEFAULT_ALLOWED_CHARS;
        }
        if (!CharSetUtils.keep(userName, allowed).equals(userName)) {
            throw ApiException.badRequest("userName contains characters that are not allowed.");
        }

        String emailAddress = body.emailAddress() == null ? null : body.emailAddress().trim();
        if (StringUtils.isBlank(emailAddress) || !EMAIL_PATTERN.matcher(emailAddress).matches()) {
            throw ApiException.badRequest("A valid emailAddress is required.");
        }

        String screenName = StringUtils.isBlank(body.screenName()) ? userName : body.screenName().trim();

        UserManager mgr = weblogger.getUserManager();
        // Checked with enabled=null (any status) rather than relying on
        // UserManager.addUser's own duplicate check, which only looks among
        // ENABLED users -- a same-named disabled account would otherwise
        // reach addUser and fail as a bare, unguarded WebloggerException.
        if (mgr.getUserByUserName(userName, null) != null
                || mgr.getUserByUserName(userName.toLowerCase(), null) != null) {
            throw ApiException.conflict("A user named '" + userName + "' already exists.");
        }

        if (!PasswordLinkMailer.isReady()) {
            throw ApiException.badRequest(
                    "Mail is not configured, so a set-password link cannot be delivered. "
                            + "An account with no way to reach a usable password must not be created.");
        }

        User user = new User();
        user.setUserName(userName);
        user.setScreenName(screenName);
        // roller_user.fullname is NOT NULL and UserCreate carries no field
        // for it -- adding one just to satisfy a column an automation
        // caller has no reason to fill in would be a worse API than
        // defaulting it the same way screenName already defaults to
        // userName above.
        user.setFullName(screenName);
        user.setEmailAddress(emailAddress);
        user.setEnabled(Boolean.FALSE);
        user.setDateCreated(new Date());
        // Never accepted from the caller -- see class javadoc. A random
        // value nobody is ever told, matching CreateUserBean's own
        // blank-password branch; the account is unusable until the emailed
        // link sets a real one.
        user.resetPassword(TokenGenerator.newToken());

        mgr.addUser(user);
        weblogger.flush();

        try {
            String raw = weblogger.getUserTokenManager().issueToken(user, UserToken.Purpose.PASSWORD_SET);
            weblogger.flush();
            PasswordLinkMailer.sendLink(user, raw, "Set your Roller password");
        } catch (MessagingException e) {
            // The account exists either way -- an admin can resend the link
            // from the JSP UserAdmin screen's own action. Reported as a
            // 502-shaped client-visible failure rather than swallowed, since
            // the caller needs to know delivery did not happen.
            throw ApiException.badRequest(
                    "The account was created, but the set-password link could not be sent.");
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(AdminDtos.toView(user, roles(user)));
    }

    @PatchMapping("/users/{userName}")
    public AdminDtos.UserView patchUser(
            @PathVariable("userName") String userName, @RequestBody AdminDtos.UserPatch body)
            throws WebloggerException {
        if (StringUtils.isBlank(userName)) {
            throw ApiException.notFound("No such user.");
        }
        UserManager mgr = weblogger.getUserManager();
        User user = mgr.getUserByUserName(userName, null);
        if (user == null) {
            throw ApiException.notFound("No such user.");
        }

        if (body.emailAddress() != null) {
            String emailAddress = body.emailAddress().trim();
            if (!EMAIL_PATTERN.matcher(emailAddress).matches()) {
                throw ApiException.badRequest("emailAddress is not a valid address.");
            }
            user.setEmailAddress(emailAddress);
        }
        if (body.screenName() != null) {
            String screenName = body.screenName().trim();
            if (screenName.isBlank()) {
                throw ApiException.badRequest("screenName cannot be blank.");
            }
            user.setScreenName(screenName);
        }
        if (body.enabled() != null) {
            user.setEnabled(body.enabled());
        }

        mgr.saveUser(user);
        weblogger.flush();
        return AdminDtos.toView(user, roles(user));
    }

    @SuppressWarnings("deprecation") // getRoles() is the only way to read a user's actual roles
    private List<String> roles(User user) throws WebloggerException {
        return weblogger.getUserManager().getRoles(user);
    }

    // ---------------------------------------------------------- config

    /**
     * Every runtime-settable property, current value first, falling back to
     * its declared default when no row has ever been saved for it.
     */
    @GetMapping("/config")
    public List<AdminDtos.ConfigEntry> listConfig() throws WebloggerException {
        List<AdminDtos.ConfigEntry> entries = new ArrayList<>();
        for (ConfigDef configDef : WebloggerRuntimeConfig.getRuntimeConfigDefs().getConfigDefs()) {
            for (DisplayGroup group : configDef.getDisplayGroups()) {
                for (PropertyDef def : group.getPropertyDefs()) {
                    entries.add(new AdminDtos.ConfigEntry(def.getName(), currentValue(def), def.getType()));
                }
            }
        }
        return entries;
    }

    /**
     * Body is a flat name-to-new-value map, not a list of {@code
     * ConfigEntry} -- the {@code type} is declared by {@code
     * runtimeConfigDefs.xml}, not something a caller gets to assert, and a
     * map is the natural shape for "set these properties" either way. Every
     * name is checked against {@link AdminDtos#requireRuntimeProperty} --
     * the allowlist -- before anything is saved, and every value is checked
     * against its declared type, so a whole-request failure never leaves a
     * partial write behind.
     */
    @PatchMapping("/config")
    public List<AdminDtos.ConfigEntry> patchConfig(@RequestBody Map<String, String> body)
            throws WebloggerException {
        if (body == null || body.isEmpty()) {
            throw ApiException.badRequest("At least one property is required.");
        }

        Map<String, RuntimeConfigProperty> toSave = new LinkedHashMap<>();
        List<AdminDtos.ConfigEntry> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : body.entrySet()) {
            String name = entry.getKey();
            AdminDtos.requireRuntimeProperty(name);
            PropertyDef def = AdminDtos.findPropertyDef(name);
            String value = entry.getValue();
            requireValidType(def, value);

            // Every runtime property is pre-seeded into roller_properties at
            // startup (JPAPropertiesManagerImpl.initializeMissingProps), so
            // this is (almost) always an update, never an insert.
            // JPAPersistenceStrategy.store() calls em.persist() outright for
            // any not-yet-managed instance -- it does not upsert -- so
            // handing it a bare `new RuntimeConfigProperty(name, value)` for
            // a name that already has a row throws a raw, unguarded
            // duplicate-key DatabaseException. Loading the existing managed
            // row and mutating it in place is the same pattern
            // GlobalConfigController's save uses for the JSP admin screen.
            RuntimeConfigProperty property = weblogger.getPropertiesManager().getProperty(name);
            if (property != null) {
                property.setValue(value);
            } else {
                property = new RuntimeConfigProperty(name, value);
            }
            toSave.put(name, property);
            result.add(new AdminDtos.ConfigEntry(name, value, def.getType()));
        }

        weblogger.getPropertiesManager().saveProperties(toSave);
        weblogger.flush();
        return result;
    }

    private String currentValue(PropertyDef def) throws WebloggerException {
        RuntimeConfigProperty stored = weblogger.getPropertiesManager().getProperty(def.getName());
        if (stored != null && stored.getValue() != null) {
            return stored.getValue();
        }
        return def.getDefaultValue();
    }

    /**
     * A property value of the wrong shape for its declared type (an
     * unparsable integer/float, or anything but true/false for a boolean)
     * is a 400 here rather than whatever downstream code first tries to
     * parse the stored string and throws an unguarded exception.
     */
    private static void requireValidType(PropertyDef def, String value) {
        if (value == null) {
            throw ApiException.badRequest("'" + def.getName() + "' cannot be set to null.");
        }
        switch (def.getType()) {
            case "boolean" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw ApiException.badRequest("'" + def.getName() + "' must be true or false.");
                }
            }
            case "integer" -> {
                try {
                    Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("'" + def.getName() + "' must be an integer.");
                }
            }
            case "float" -> {
                try {
                    Float.parseFloat(value);
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("'" + def.getName() + "' must be a number.");
                }
            }
            default -> {
                // string / text: no format constraint.
            }
        }
    }

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of();
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }
}

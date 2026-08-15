package org.apache.roller.weblogger.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.config.runtime.PropertyDef;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.restapi.ApiException;

/**
 * Views for the admin surface: user accounts, weblog administration, and
 * runtime configuration, for the automation API.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserView(String userName, String screenName, String emailAddress,
                            boolean enabled, List<String> globalRoles) {
    }

    /**
     * The body of a create-user request. Deliberately carries no password
     * field -- {@link org.apache.roller.weblogger.ui.restapi.v1.AdminApi}
     * creates the account disabled and emails a set-password link instead,
     * so no plaintext password ever crosses this API.
     */
    public record UserCreate(String userName, String screenName, String emailAddress) {
    }

    /**
     * The body of a user PATCH. A null field leaves the corresponding
     * property unchanged; {@code userName} is the path variable and is not
     * itself patchable.
     */
    public record UserPatch(Boolean enabled, String screenName, String emailAddress) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfigEntry(String name, String value, String type) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WeblogView(String id, String handle, String name, String tagline,
                              String emailAddress, String locale, String timeZone,
                              int entryDisplayCount, boolean active, String editorTheme,
                              String creatorUserName) {
    }

    /**
     * The body of a weblog PATCH. A null field leaves the corresponding
     * property unchanged.
     */
    public record WeblogPatch(String name, String tagline, String emailAddress, String locale,
                               String timeZone, Integer entryDisplayCount, Boolean active) {
    }

    public static UserView toView(User user, List<String> roles) {
        return new UserView(user.getUserName(), user.getScreenName(), user.getEmailAddress(),
                Boolean.TRUE.equals(user.getEnabled()), roles);
    }

    public static WeblogView toView(Weblog weblog) {
        return new WeblogView(weblog.getId(), weblog.getHandle(), weblog.getName(),
                weblog.getTagline(), weblog.getEmailAddress(), weblog.getLocale(),
                weblog.getTimeZone(), weblog.getEntryDisplayCount(),
                Boolean.TRUE.equals(weblog.getActive()), weblog.getEditorTheme(),
                weblog.getCreatorUserName());
    }

    /**
     * Accepts a property name only if runtimeConfigDefs.xml declares it.
     *
     * <p>The declaration file IS the allowlist -- there is no second hardcoded
     * list to drift from it. A startup-scoped setting is absent from
     * runtimeConfigDefs.xml by definition, so it is rejected here for free.
     */
    public static void requireRuntimeProperty(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("A property name is required.");
        }
        if (findPropertyDef(name) == null) {
            throw ApiException.badRequest(
                    "'" + name + "' is not a runtime-settable property.");
        }
    }

    /**
     * The declared definition for a runtime property name, or {@code null}
     * when {@code runtimeConfigDefs.xml} declares no such property -- which
     * is also the case, by construction, for every startup-scoped setting.
     *
     * <p>{@code RuntimeConfigDefs} groups its {@code PropertyDef}s two levels
     * down ({@code ConfigDef} -> {@code DisplayGroup} -> {@code PropertyDef}),
     * not directly, so this walks all three rather than assuming a flatter
     * shape.
     */
    public static PropertyDef findPropertyDef(String name) {
        if (name == null || name.isBlank() || WebloggerRuntimeConfig.getRuntimeConfigDefs() == null) {
            return null;
        }
        return WebloggerRuntimeConfig.getRuntimeConfigDefs().getConfigDefs().stream()
                .flatMap(configDef -> configDef.getDisplayGroups().stream())
                .flatMap(group -> group.getPropertyDefs().stream())
                .filter(def -> name.equals(def.getName()))
                .findFirst()
                .orElse(null);
    }
}

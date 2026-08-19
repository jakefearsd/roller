package org.apache.roller.weblogger.ui.restapi.auth;

import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.util.GenericThrottle;

/**
 * Wraps {@link GenericThrottle} for the automation API, following the same
 * shape {@code ContactController} uses for its own throttle.
 *
 * <p>Sizing (threshold/interval/maxentries) is read once, at construction --
 * it dimensions a fixed tracking cache that cannot be resized under live
 * callers, the same reason the contact and newsletter throttles size
 * themselves at startup. The on/off switch, {@code api.throttle.enabled}, is
 * read fresh on every call instead, through {@link WebloggerRuntimeConfig},
 * so it can be flipped from Admin Settings without a restart.
 */
public final class ApiThrottle {

    private final GenericThrottle throttle;

    /**
     * Non-null only for the test/disabled fixtures below, where there is no
     * running application (and so no runtime config) to consult.
     */
    private final Boolean forcedEnabled;

    private ApiThrottle(GenericThrottle throttle, Boolean forcedEnabled) {
        this.throttle = throttle;
        this.forcedEnabled = forcedEnabled;
    }

    /** The production throttle, sized from {@code roller.properties}. */
    public static ApiThrottle create() {
        int threshold = intProperty("api.throttle.threshold", 120);
        int interval = intProperty("api.throttle.interval", 60);
        int maxEntries = intProperty("api.throttle.maxentries", 500);
        return new ApiThrottle(
                new GenericThrottle(threshold, interval * RollerConstants.SEC_IN_MS, maxEntries), null);
    }

    /** A throttle sized directly, bypassing runtime config entirely. */
    static ApiThrottle forTesting(int threshold, int intervalSeconds) {
        return new ApiThrottle(
                new GenericThrottle(threshold, intervalSeconds * RollerConstants.SEC_IN_MS, 1000),
                Boolean.TRUE);
    }

    /** A throttle that never throttles, for {@code api.throttle.enabled=false}. */
    static ApiThrottle disabled() {
        return new ApiThrottle(null, Boolean.FALSE);
    }

    /**
     * Records a hit for {@code key} and reports whether it has now passed
     * the threshold.
     */
    public boolean isThrottled(String key) {
        boolean enabled = forcedEnabled != null
                ? forcedEnabled
                : WebloggerRuntimeConfig.getBooleanProperty("api.throttle.enabled");
        return enabled && throttle.processHit(key);
    }

    private static int intProperty(String name, int fallback) {
        try {
            return Integer.parseInt(WebloggerConfig.getProperty(name));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

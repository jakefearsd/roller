package org.apache.roller.weblogger.business;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.Weblog;

/**
 * The hostname to weblog-handle map, held in memory.
 *
 * <p>Two callers need it and neither can afford a database round trip:
 * {@code WeblogRequestMapper} runs on every public request, and the
 * control-plane redirect filter runs at filter order 35 -- ahead of the Spring
 * Security chain (40) and therefore ahead of {@code PersistenceSessionFilter}
 * (60), so it has no {@code EntityManager} at all.
 *
 * <p>The map is loaded lazily and rebuilt after any weblog save. Before Roller
 * has bootstrapped there are no weblogs, so an empty map is the correct answer
 * rather than an error -- which is what lets the filter run that early.
 */
public final class VirtualHostRegistry {

    private static final Log log = LogFactory.getLog(VirtualHostRegistry.class);

    private static volatile Map<String, String> hostToHandle = null;

    private VirtualHostRegistry() {
    }

    /**
     * Lowercases a Host header and strips the port and any trailing root
     * label, returning null for anything unusable.
     */
    public static String normalise(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);

        // An IPv6 literal is bracketed and full of colons that are not port
        // separators, so only a port AFTER the closing bracket may be removed.
        int portAt = host.startsWith("[")
                ? host.indexOf(':', host.indexOf(']') + 1)
                : host.indexOf(':');
        if (portAt >= 0) {
            host = host.substring(0, portAt);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.isBlank() ? null : host;
    }

    /** The handle of the weblog serving this Host header, or null. */
    public static String handleFor(String hostHeader) {
        String host = normalise(hostHeader);
        return host == null ? null : map().get(host);
    }

    /** The hostname this weblog is served at, or null if it has none. */
    public static String hostFor(String handle) {
        if (handle == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : map().entrySet()) {
            if (handle.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Drops the cached map; the next read rebuilds it. */
    public static void invalidate() {
        hostToHandle = null;
    }

    private static Map<String, String> map() {
        Map<String, String> current = hostToHandle;
        if (current != null) {
            return current;
        }
        Map<String, String> built = new HashMap<>();
        try {
            for (Weblog weblog : WebloggerFactory.getWeblogger()
                    .getWeblogManager().getWeblogs(null, null, null, null, 0, -1)) {
                String host = normalise(weblog.getCustomDomain());
                if (host != null) {
                    built.put(host, weblog.getHandle());
                }
            }
        } catch (WebloggerException | RuntimeException e) {
            // Before bootstrap there is nothing to read and no weblog can have
            // a domain, so an empty map is correct rather than an error. Do
            // NOT cache it in that case, or the map stays empty for the life
            // of the JVM.
            log.debug("Virtual-host map unavailable yet; treating as empty", e);
            return Collections.emptyMap();
        }
        Map<String, String> immutable = Collections.unmodifiableMap(built);
        hostToHandle = immutable;
        return immutable;
    }
}

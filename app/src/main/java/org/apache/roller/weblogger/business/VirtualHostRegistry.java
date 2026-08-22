package org.apache.roller.weblogger.business;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>The map is loaded lazily from the {@link WeblogManager} this instance is
 * constructed with, and rebuilt after any weblog save
 * ({@code JPAWeblogManagerImpl} calls {@link #invalidate()}). It is a bean in
 * {@code WebloggerBeanConfig}, built with the rest of the business tier and
 * reachable as {@link Weblogger#getVirtualHostRegistry()}.
 *
 * <p><b>TRANSITIONAL:</b> until plan Task 6 hands the filters, the request
 * mapper and {@code SeoController} the instance directly, they reach it through
 * the static {@code *Current} delegators below, which resolve the bootstrapped
 * tier's registry through the {@code WebloggerFactory} locator -- the one
 * static this wave is retiring, deliberately reused rather than duplicated, so
 * the test suite's existing install/restore discipline for that locator covers
 * this too. Before the tier is up a lookup answers "no weblog" rather than
 * throwing, which is what lets the control-plane filter run that early. Task 6
 * deletes the delegators and takes this file off
 * {@code StaticServiceLocatorTest.ALLOWED}.
 */
public final class VirtualHostRegistry {

    private static final Logger log = LoggerFactory.getLogger(VirtualHostRegistry.class);

    private final WeblogManager weblogManager;
    private volatile Map<String, String> hostToHandle;

    public VirtualHostRegistry(WeblogManager weblogManager) {
        this.weblogManager = weblogManager;
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
    public String handleFor(String hostHeader) {
        String host = normalise(hostHeader);
        return host == null ? null : map().get(host);
    }

    /** The hostname this weblog is served at, or null if it has none. */
    public String hostFor(String handle) {
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
    public void invalidate() {
        hostToHandle = null;
    }

    private Map<String, String> map() {
        Map<String, String> cached = hostToHandle;
        if (cached != null) {
            return cached;
        }
        Map<String, String> built = new HashMap<>();
        try {
            for (Weblog weblog : weblogManager.getWeblogs(null, null, null, null, 0, -1)) {
                String host = normalise(weblog.getCustomDomain());
                if (host != null) {
                    built.put(host, weblog.getHandle());
                }
            }
        } catch (WebloggerException | RuntimeException e) {
            // Not the expected steady state: it means every custom domain has
            // stopped resolving site-wide. Warn so a live failure is actually
            // visible (M9), and do NOT cache the empty result, or the map
            // stays empty for the life of the JVM.
            log.warn("Virtual-host map rebuild failed; every custom domain will fail to "
                    + "resolve until this is fixed and something invalidates the map again", e);
            return Collections.emptyMap();
        }
        Map<String, String> immutable = Collections.unmodifiableMap(built);
        hostToHandle = immutable;
        return immutable;
    }

    // ------------------------------------------------------------------------
    // TRANSITIONAL static delegators -- removed by plan Task 6.

    /**
     * The bootstrapped tier's registry, or null before bootstrap (the
     * expected, quiet case) or when the facade has none (a mocked facade in a
     * test that did not stub it).
     */
    private static VirtualHostRegistry current() {
        if (!WebloggerFactory.isBootstrapped()) {
            return null;
        }
        return WebloggerFactory.getWeblogger().getVirtualHostRegistry();
    }

    /** {@link #handleFor} on the tier's registry; null before bootstrap. */
    public static String handleForCurrent(String hostHeader) {
        VirtualHostRegistry registry = current();
        return registry == null ? null : registry.handleFor(hostHeader);
    }

    /** {@link #hostFor} on the tier's registry; null before bootstrap. */
    public static String hostForCurrent(String handle) {
        VirtualHostRegistry registry = current();
        return registry == null ? null : registry.hostFor(handle);
    }

    /** {@link #invalidate} on the tier's registry; a no-op before bootstrap. */
    public static void invalidateCurrent() {
        VirtualHostRegistry registry = current();
        if (registry != null) {
            registry.invalidate();
        }
    }
}

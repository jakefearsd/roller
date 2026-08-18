package org.apache.roller.weblogger.business;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.roller.weblogger.WebloggerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** Host-header normalisation, which decides whether a request resolves at all. */
class VirtualHostRegistryTest {

    @Test
    void aPortIsStripped() {
        assertEquals("b.example.com", VirtualHostRegistry.normalise("b.example.com:8443"));
    }

    @Test
    void caseIsFolded() {
        assertEquals("b.example.com", VirtualHostRegistry.normalise("B.Example.COM"));
    }

    /** A fully-qualified name with the root label is the same host. */
    @Test
    void aTrailingDotIsStripped() {
        assertEquals("b.example.com", VirtualHostRegistry.normalise("b.example.com."));
    }

    @Test
    void nullAndBlankNormaliseToNull() {
        assertNull(VirtualHostRegistry.normalise(null));
        assertNull(VirtualHostRegistry.normalise("   "));
    }

    /**
     * An IPv6 literal Host header is bracketed and contains colons that are not
     * a port separator. Stripping at the first colon would corrupt it, so the
     * brackets are kept and only a port after the closing bracket is removed.
     */
    @Test
    void anIpv6LiteralKeepsItsColons() {
        assertEquals("[::1]", VirtualHostRegistry.normalise("[::1]:8080"));
        assertEquals("[::1]", VirtualHostRegistry.normalise("[::1]"));
    }

    // -------------------------------------------------- map() failure logging

    /** Captures the log events VirtualHostRegistry's own logger actually emits. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        CapturingAppender() {
            super("virtual-host-registry-test-appender", null, null, false, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private CapturingAppender appender;
    private Logger logger;
    private Level previousLevel;

    private void startCapturingLogs() {
        logger = (Logger) LogManager.getLogger(VirtualHostRegistry.class);
        previousLevel = logger.getLevel();
        appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);
    }

    @AfterEach
    void stopCapturingLogs() {
        if (logger != null) {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
        }
        if (appender != null) {
            appender.stop();
        }
        VirtualHostRegistry.invalidate();
        MockWeblogger.uninstall();
    }

    /**
     * M9: after bootstrap, an exception here means every custom domain stops
     * resolving site-wide -- that must not be a debug-only line nobody
     * enables in production.
     */
    @Test
    void mapFailureLogsAtWarnOnceBootstrapped() throws WebloggerException {
        startCapturingLogs();
        MockWeblogger mocks = MockWeblogger.install();
        when(mocks.getWeblogManager().getWeblogs(null, null, null, null, 0, -1))
                .thenThrow(new WebloggerException("boom"));

        VirtualHostRegistry.handleFor("anything.example.com");

        assertTrue(appender.events.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                "map() must log at WARN once the app has bootstrapped, so a live failure "
                        + "is not silently invisible at the default log level");
    }

    /**
     * Before bootstrap, {@code WebloggerFactory.getWeblogger()} throwing is
     * the EXPECTED steady state (see the class javadoc: "before Roller has
     * bootstrapped there are no weblogs"), not a live failure -- keep this
     * one quiet.
     */
    @Test
    void mapFailureStaysQuietBeforeBootstrap() {
        startCapturingLogs();
        MockWeblogger.installNotBootstrapped();

        VirtualHostRegistry.handleFor("anything.example.com");

        assertFalse(appender.events.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                "a pre-bootstrap lookup is expected and must not log at WARN");
    }
}

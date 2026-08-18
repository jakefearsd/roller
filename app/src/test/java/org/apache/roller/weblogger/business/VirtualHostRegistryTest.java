package org.apache.roller.weblogger.business;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}

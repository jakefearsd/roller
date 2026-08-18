package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Url generation for a weblog that owns a hostname.
 *
 * <p>These are pure-function tests on the strategy: they build a detached
 * Weblog rather than persisting one, because getWeblogURL reads only the
 * handle and the custom domain.
 */
class MultiWeblogURLStrategyTest {

    private final MultiWeblogURLStrategy strategy = new MultiWeblogURLStrategy();

    private String previousRelativeContextURL;

    /**
     * WebloggerRuntimeConfig's relative/absolute context url fields are
     * per-JVM statics that other test classes set and don't reliably restore
     * (the "CI order dependency" noted in project docs) -- so the
     * non-custom-domain branch below would otherwise see whatever an
     * unrelated test left behind, depending on run order. Pinning it here
     * (the same save/restore shape URLModelTest already uses for this same
     * static state) is what makes the characterisation assertion below
     * deterministic rather than order-dependent.
     */
    @BeforeEach
    void pinRelativeContextURL() {
        previousRelativeContextURL = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("");
    }

    @AfterEach
    void restoreRelativeContextURL() {
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelativeContextURL);
    }

    private static Weblog weblog(String handle, String customDomain) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setCustomDomain(customDomain);
        return weblog;
    }

    @Test
    void anAbsoluteUrlUsesTheCustomDomainAndDropsTheHandle() {
        assertEquals("https://vhost.example.com/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), null, true));
    }

    /**
     * Root-relative with no handle segment. The page is only ever served on its
     * own domain (any other host 301s), so a relative link needs no host -- and
     * it must not carry the handle, which does not exist in that url space.
     */
    @Test
    void aRelativeUrlDropsTheHandleToo() {
        assertEquals("/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), null, false));
    }

    @Test
    void aLocaleStillFollowsTheWeblogRoot() {
        assertEquals("https://vhost.example.com/de/",
                strategy.getWeblogURL(weblog("vhostblog", "vhost.example.com"), "de", true));
    }

    @Test
    void anEntryUrlIsBuiltOnTheCustomDomainRoot() {
        assertEquals("https://vhost.example.com/entry/my-post",
                strategy.getWeblogEntryURL(
                        weblog("vhostblog", "vhost.example.com"), null, "my-post", true));
    }

    /**
     * CHARACTERISATION: a weblog with no custom domain keeps today's shape
     * exactly. Expected to pass on arrival.
     */
    @Test
    void aWeblogWithoutACustomDomainIsUnchanged() {
        assertEquals("/plainblog/",
                strategy.getWeblogURL(weblog("plainblog", null), null, false));
    }
}

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
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.business.plugins.PluginManager;
import org.mockito.Mockito;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.business.runnable.ThreadManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Installs a fully-mocked {@link Weblogger} so code that reaches the business tier
 * through {@code WebloggerFactory.getWeblogger()} can be unit tested.
 *
 * <p>Roller's controllers call {@code WebloggerFactory.getWeblogger().getXManager()}
 * from 179 places. That static lookup is why the controller packages sat at roughly
 * one percent coverage: exercising a single handler otherwise meant standing up a
 * database, bootstrapping the whole application, and accepting a multi-second test.
 *
 * <p>This class lives in {@code org.apache.roller.weblogger.business} so it can reach
 * the package-private {@link WebloggerFactory#installProvider} seam. Nothing in
 * production can.
 *
 * <p>Typical use:
 *
 * <pre>{@code
 * private MockWeblogger weblogger;
 *
 * @BeforeEach void setUp() { weblogger = MockWeblogger.install(); }
 * @AfterEach  void tearDown() { MockWeblogger.uninstall(); }
 *
 * @Test void findsTheWeblog() throws Exception {
 *     when(weblogger.weblogManager().getWeblogByHandle("blog")).thenReturn(aWeblog);
 *     ...
 * }
 * }</pre>
 *
 * <p>Every manager is a Mockito mock with no stubbed behaviour, so an unstubbed call
 * returns null rather than throwing. Tests stub only what they need.
 */
public final class MockWeblogger {

    private final Weblogger weblogger = mock(Weblogger.class);

    private final UserManager userManager = mock(UserManager.class);
    private final WeblogManager weblogManager = mock(WeblogManager.class);
    private final WeblogEntryManager weblogEntryManager = mock(WeblogEntryManager.class);
    private final PropertiesManager propertiesManager = mock(PropertiesManager.class);
    private final ThreadManager threadManager = mock(ThreadManager.class);
    private final IndexManager indexManager = mock(IndexManager.class);
    private final ThemeManager themeManager = mock(ThemeManager.class);
    private final PluginManager pluginManager = mock(PluginManager.class);
    private final MediaFileManager mediaFileManager = mock(MediaFileManager.class);
    private final FileContentManager fileContentManager = mock(FileContentManager.class);
    private final URLStrategy urlStrategy = mock(URLStrategy.class);

    private int flushCount;

    private MockWeblogger() throws org.apache.roller.weblogger.WebloggerException {
        when(weblogger.getUserManager()).thenReturn(userManager);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getWeblogEntryManager()).thenReturn(weblogEntryManager);
        when(weblogger.getPropertiesManager()).thenReturn(propertiesManager);
        when(weblogger.getThreadManager()).thenReturn(threadManager);
        when(weblogger.getIndexManager()).thenReturn(indexManager);
        when(weblogger.getThemeManager()).thenReturn(themeManager);
        when(weblogger.getPluginManager()).thenReturn(pluginManager);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFileManager);
        when(weblogger.getFileContentManager()).thenReturn(fileContentManager);
        when(weblogger.getUrlStrategy()).thenReturn(urlStrategy);

        // Count commits. A manager call that is never flushed is a change that
        // never reaches the database, and nothing else these tests can observe
        // tells the two apart.
        Mockito.doAnswer(invocation -> {
            flushCount++;
            return null;
        }).when(weblogger).flush();
    }

    public URLStrategy getUrlStrategy() {
        return urlStrategy;
    }

    public URLStrategy urlStrategy() {
        return urlStrategy;
    }

    /** How many times the code under test committed its work. */
    public int flushCount() {
        return flushCount;
    }

    /**
     * Installs a mocked business tier and returns the handle for stubbing it.
     *
     * <p>Call {@link #uninstall()} from {@code @AfterEach}. The factory holds static
     * state, so a test that installs without uninstalling leaks its mocks into
     * whatever runs next in the same JVM.
     */
    private static WebloggerProvider previousProvider;

    public static MockWeblogger install() {
        MockWeblogger mocks;
        try {
            mocks = new MockWeblogger();
        } catch (org.apache.roller.weblogger.WebloggerException e) {
            // Only the Mockito stubbing above declares it; it cannot actually throw.
            throw new IllegalStateException("Could not build the mock business tier", e);
        }
        previousProvider = WebloggerFactory.currentProvider();
        WebloggerFactory.installProvider(new WebloggerProvider() {
            @Override
            public void bootstrap() {
                // already built
            }

            @Override
            public Weblogger getWeblogger() {
                return mocks.weblogger;
            }
        });
        return mocks;
    }

    /**
     * Restores whatever provider was installed before, rather than clearing it.
     *
     * <p>The database-backed tests bootstrap a real Weblogger into this same static
     * field and reuse it for the whole JVM. Nulling it here would force them to
     * bootstrap again mid-run, or fail outright, depending on test order.
     */
    public static void uninstall() {
        WebloggerFactory.installProvider(previousProvider);
        previousProvider = null;
    }

    /**
     * Leaves the factory reporting itself NOT bootstrapped.
     *
     * <p>InstallController and SetupController branch on
     * {@code WebloggerFactory.isBootstrapped()}, so the pre-bootstrap path is
     * only reachable with no provider installed at all.
     */
    public static void installNotBootstrapped() {
        previousProvider = WebloggerFactory.currentProvider();
        WebloggerFactory.installProvider(null);
    }

    public Weblogger weblogger() {
        return weblogger;
    }

    // getX() aliases mirror the Weblogger facade, so a test can read either
    // mocks.weblogManager() or mocks.getWeblogManager() without surprise.
    public UserManager getUserManager() {
        return userManager;
    }

    public WeblogManager getWeblogManager() {
        return weblogManager;
    }

    public WeblogEntryManager getWeblogEntryManager() {
        return weblogEntryManager;
    }

    public PropertiesManager getPropertiesManager() {
        return propertiesManager;
    }

    public ThreadManager getThreadManager() {
        return threadManager;
    }

    public IndexManager getIndexManager() {
        return indexManager;
    }

    public ThemeManager getThemeManager() {
        return themeManager;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    public MediaFileManager getMediaFileManager() {
        return mediaFileManager;
    }

    public FileContentManager getFileContentManager() {
        return fileContentManager;
    }

    public UserManager userManager() {
        return userManager;
    }

    public WeblogManager weblogManager() {
        return weblogManager;
    }

    public WeblogEntryManager weblogEntryManager() {
        return weblogEntryManager;
    }

    public PropertiesManager propertiesManager() {
        return propertiesManager;
    }

    public ThreadManager threadManager() {
        return threadManager;
    }

    public IndexManager indexManager() {
        return indexManager;
    }

    public ThemeManager themeManager() {
        return themeManager;
    }

    public PluginManager pluginManager() {
        return pluginManager;
    }

    public MediaFileManager mediaFileManager() {
        return mediaFileManager;
    }

    public FileContentManager fileContentManager() {
        return fileContentManager;
    }
}

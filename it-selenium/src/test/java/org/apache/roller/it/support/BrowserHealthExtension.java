/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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

package org.apache.roller.it.support;

import com.codeborne.selenide.Selenide;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

/**
 * Gives every test a freshly instrumented browser and, when the test is done, fails it if
 * the browser saw anything broken.
 *
 * <p>Declared once on {@link RollerIT}, so the whole suite inherits the checks and no
 * individual test has to remember to ask for them.
 */
public class BrowserHealthExtension implements BeforeEachCallback, AfterEachCallback {

    private static final Namespace NAMESPACE = Namespace.create(BrowserHealthExtension.class);
    private static final String HEALTH_KEY = "browserHealth";

    @Override
    public void beforeEach(ExtensionContext context) {
        // Selenide is configured from here rather than from a static initialiser because the
        // browser is about to be started and the settings only take effect beforehand.
        RollerIT.configureSelenide();
        context.getStore(NAMESPACE).put(HEALTH_KEY, BrowserHealth.attach());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        BrowserHealth health = context.getStore(NAMESPACE).remove(HEALTH_KEY, BrowserHealth.class);
        try {
            if (health != null) {
                health.settle();
                // Asserted even when the test has already failed: JUnit records this as a
                // suppressed exception rather than replacing the original one, and a broken
                // stylesheet or JavaScript error is frequently the reason the test failed.
                health.assertHealthy();
            }
        } finally {
            // A brand new browser per test. DevTools listeners accumulate on a reused session,
            // which would double-count events, and a surviving login cookie would let a test
            // pass on the previous test's session instead of establishing its own.
            Selenide.closeWebDriver();
            BrowserHealth.detach();
        }
    }
}

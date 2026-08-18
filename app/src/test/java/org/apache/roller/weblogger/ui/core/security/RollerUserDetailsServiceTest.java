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
package org.apache.roller.weblogger.ui.core.security;

import org.apache.roller.weblogger.business.MockWeblogger;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link RollerUserDetailsService}.
 *
 * <p>Before the business tier is bootstrapped, {@code
 * WebloggerFactory.getWeblogger()} throws {@code IllegalStateException} --
 * Spring Security still calls into this service that early (first-time
 * setup), so the failure is converted to a "soft" {@code
 * UsernameNotFoundException} that lets the setup flow proceed, rather than
 * a hard 500. The cause must still be attached, so it stays diagnosable.
 */
class RollerUserDetailsServiceTest {

    private final RollerUserDetailsService service = new RollerUserDetailsService();

    @Test
    void anUnbootstrappedFactoryProducesASoftFailureCarryingItsCause() {
        MockWeblogger.installNotBootstrapped();
        try {
            UsernameNotFoundException thrown = assertThrows(UsernameNotFoundException.class,
                    () -> service.loadUserByUsername("anyone"));

            assertNotNull(thrown.getCause(),
                    "the IllegalStateException from getWeblogger() must survive as the cause");
        } finally {
            MockWeblogger.uninstall();
        }
    }
}

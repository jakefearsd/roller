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
package org.apache.roller.weblogger.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WebloggerRuntimeConfig#getRuntimeConfigDefsAsString()},
 * previously uncovered.
 */
class WebloggerRuntimeConfigTest {

    @Test
    void getRuntimeConfigDefsAsStringReturnsTheActualXmlFileContent() {
        String defs = WebloggerRuntimeConfig.getRuntimeConfigDefsAsString();

        assertTrue(defs.contains("<runtime-configs>"),
                "must be the real runtimeConfigDefs.xml, not the empty-string error fallback");
        assertTrue(defs.contains("site.name"),
                "must contain a known runtime property definition");
    }
}

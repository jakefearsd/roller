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
package org.apache.roller.weblogger.business.startup;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MigrationCatalog#versions()} itself is covered end to end by
 * {@link SchemaMigrationTest} (a real classpath directory of migrations).
 * {@link MigrationCatalog#fileNameOf} is pinned directly here for the one
 * case a real directory-stream entry cannot produce: a path with no name
 * elements, where {@code Path.getFileName()} returns null
 * (NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE).
 */
class MigrationCatalogTest {

    @Test
    void fileNameOfReturnsTheLastElement() {
        assertEquals("V001__init.sql", MigrationCatalog.fileNameOf(Path.of("/dbmigrations/V001__init.sql")));
    }

    @Test
    void fileNameOfIsNullForAZeroElementPath() {
        assertNull(MigrationCatalog.fileNameOf(Path.of("/")));
    }

    @Test
    void versionsListsTheRealMigrationsOnTheClasspath() {
        List<String> versions = MigrationCatalog.versions();
        assertFalse(versions.isEmpty(), "the build must copy bin/db/migrations onto the classpath");
    }
}

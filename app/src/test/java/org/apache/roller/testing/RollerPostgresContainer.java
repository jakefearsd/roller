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
package org.apache.roller.testing;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Lazily started PostgreSQL container shared by every test in the JVM.
 *
 * <p>Roller became PostgreSQL-only during this fork's simplification, and
 * tests run against the same database engine as production rather than
 * the embedded Derby they used before. The container's schema is built by
 * applying the real
 * {@code bin/db/migrations} chain (see {@link RollerDatabaseExtension}), so
 * there is no second, hand-maintained test schema to drift out of sync.
 *
 * <p>One container per JVM, not per class: starting Postgres costs a second or
 * two, and {@link RollerDatabaseExtension} gives each test a clean database by
 * truncating rather than by recreating the container.
 */
public final class RollerPostgresContainer {

    /** Pinned to match docker-compose.yml. */
    private static final String IMAGE = "postgres:16";

    private static volatile PostgreSQLContainer container;

    private RollerPostgresContainer() {
    }

    public static PostgreSQLContainer get() {
        if (container == null) {
            synchronized (RollerPostgresContainer.class) {
                if (container == null) {
                    PostgreSQLContainer started = new PostgreSQLContainer(IMAGE)
                            .withDatabaseName("rollerdb")
                            .withUsername("roller")
                            .withPassword("roller");
                    started.start();
                    container = started;
                }
            }
        }
        return container;
    }

    public static String getJdbcUrl() {
        return get().getJdbcUrl();
    }

    public static String getUsername() {
        return get().getUsername();
    }

    public static String getPassword() {
        return get().getPassword();
    }
}

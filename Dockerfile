# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  The ASF licenses this file to You
# under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.  For additional information regarding
# copyright in this work, please see the NOTICE file in the top level
# directory of this distribution.

# Production image for Roller, built from THIS tree (never from an upstream
# release tag or a fresh git clone). Two stages:
#
#   1. builder -- Maven reactor build of the app module only, producing the
#      executable WAR (spring-boot-maven-plugin repackages it; see
#      app/pom.xml). Layer caching is intentionally NOT optimized here: this
#      reactor (root pom + app, with bin/db/migrations pulled in as a
#      resource at generate-resources time) doesn't play well with
#      `mvn dependency:go-offline` as a separate cached layer, so every build
#      re-resolves dependencies. Acceptable for now; revisit if build time
#      becomes a problem.
#   2. runtime -- just a JRE, the WAR, baked-in themes, and the migrations
#      SQL for reference/exec-based use by deploy tooling (Stage 1C Task 3).
#
# Run: java -jar roller.war (Boot's embedded Tomcat serves the app at
# /roller on 8080, and a second embedded connector serves
# /actuator/health on the management port 8090 -- see
# app/src/main/resources/application.properties). Runtime config is supplied
# via -Droller.custom.config=/config/roller-production.properties (see
# WebloggerConfig.java), which must set at least database.configurationType,
# database.jdbc.*, mediafiles.storage.dir=/data/mediafiles,
# search.index.dir=/data/search-index, uploads.dir=/data/uploads, and
# mail.configurationType + SMTP keys.

# ---- Stage 1: build -------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25@sha256:7e461cec477077c1d9e50b13df8aef9018764410f4c4cd7c34803f10c4c99e4c AS builder

WORKDIR /build

# Minimal reactor context: root pom (declares the app module), the app
# module itself, and bin/db/migrations (app/pom.xml's copy-db-migrations
# execution reads ${basedir}/../bin/db/migrations at generate-resources time
# to bake the SQL onto the WAR's classpath at /dbmigrations).
COPY pom.xml ./
COPY app app
COPY bin/db/migrations bin/db/migrations

RUN mvn -ntp -pl app -DskipTests package

# ---- Stage 2: runtime -------------------------------------------------------
FROM eclipse-temurin:25-jre@sha256:681c543d6f36c50f45e9b5226930a46203dcfa351d3670e9d0bdf0dabae53539 AS runtime

# curl is not in the base JRE image; installed for container healthchecks
# against the management port (docker exec ... curl 8090/actuator/health --
# used here and by docker-compose.prod.yml's app healthcheck in Task 2).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system roller && useradd --system --gid roller --home-dir /app --shell /usr/sbin/nologin roller

WORKDIR /app

COPY --from=builder /build/app/target/roller.war /app/roller.war
COPY --from=builder /build/app/src/main/webapp/themes /app/themes
COPY --from=builder /build/bin/db/migrations /app/migrations

# Runtime data: mediafiles, search index, uploads (all under /data per the
# runtime contract) plus /config for the mounted roller-production.properties.
RUN mkdir -p /data/mediafiles /data/search-index /data/uploads /config \
    && chown -R roller:roller /app /data /config

VOLUME ["/data"]

USER roller

EXPOSE 8080 8090

ENV JAVA_OPTS=""

# Shell form so JAVA_OPTS expands; -Droller.custom.config points at the
# operator-mounted production properties file (see docker-compose.prod.yml,
# Stage 1C Task 2).
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -Droller.custom.config=/config/roller-production.properties -jar /app/roller.war"]

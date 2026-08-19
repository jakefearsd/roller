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
package org.apache.roller.weblogger.business.jpa;

import java.sql.Timestamp;
import java.util.List;

import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.EventManager;
import org.apache.roller.weblogger.pojos.RollerEvent;
import org.apache.roller.weblogger.pojos.Weblog;

/**
 * JPA implementation of {@link EventManager}.
 */
public class JPAEventManagerImpl implements EventManager {

    private static final Logger log = LoggerFactory.getLogger(JPAEventManagerImpl.class);

    private final JPAPersistenceStrategy strategy;

    protected JPAEventManagerImpl(JPAPersistenceStrategy strategy) {
        log.debug("Instantiating JPA Event Manager");
        this.strategy = strategy;
    }

    @Override
    public void record(RollerEvent event) throws WebloggerException {
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(new Timestamp(System.currentTimeMillis()));
        }
        strategy.store(event);
    }

    @Override
    public List<RollerEvent> getEvents(Weblog weblog, int max) throws WebloggerException {
        TypedQuery<RollerEvent> query = strategy.getNamedQuery(
                "RollerEvent.getByWeblog", RollerEvent.class);
        query.setParameter(1, weblog);
        query.setMaxResults(max);
        return query.getResultList();
    }

    @Override
    public void removeEvents(Weblog weblog) throws WebloggerException {
        Query removeEvents = strategy.getNamedUpdate("RollerEvent.removeByWeblog");
        removeEvents.setParameter(1, weblog);
        removeEvents.executeUpdate();
    }
}

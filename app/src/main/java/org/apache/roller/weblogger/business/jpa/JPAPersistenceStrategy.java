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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerConfig;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import jakarta.persistence.TypedQuery;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import org.apache.roller.weblogger.business.DatabaseProvider;


/**
 * Responsible for the lowest-level interaction with the JPA API.
 */
public class JPAPersistenceStrategy {

    private static final Log logger =
        LogFactory.getFactory().getInstance(JPAPersistenceStrategy.class);

    private static final String PERSISTENCE_UNIT_NAME = "RollerPU";

    private static final String PERSISTENCE_XML_RESOURCE = "META-INF/persistence.xml";

    private static final String ECLIPSELINK_PROVIDER_CLASS_NAME =
            "org.eclipse.persistence.jpa.PersistenceProvider";

    /**
     * The thread local EntityManager.
     */
    private final ThreadLocal<EntityManager> threadLocalEntityManager = new ThreadLocal<>();
    
    /**
     * The EntityManagerFactory for this Roller instance.
     */
    private EntityManagerFactory emf = null;
    
            
    /**
     * Construct by finding JPA EntityManagerFactory.
     * @param dbProvider database configuration information for manual configuration.
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public JPAPersistenceStrategy(DatabaseProvider dbProvider) throws WebloggerException {
        String jpaConfigurationType = WebloggerConfig.getProperty("jpa.configurationType");
        if ("jndi".equals(jpaConfigurationType)) {
            // Lookup EMF via JNDI: added for Geronimo
            String emfJndiName = "java:comp/env/" + WebloggerConfig.getProperty("jpa.emf.jndi.name");
            try {
                emf = (EntityManagerFactory) new InitialContext().lookup(emfJndiName);
            } catch (NamingException e) {
                throw new WebloggerException("Could not look up EntityManagerFactory in jndi at " + emfJndiName, e);
            }
        } else {

            // Add all JPA, OpenJPA, HibernateJPA, etc. properties found
            Properties emfProps = new Properties();
            Enumeration<Object> keys = WebloggerConfig.keys();
            while (keys.hasMoreElements()) {
                String key = (String) keys.nextElement();
                if (       key.startsWith("jakarta.persistence.")
                        || key.startsWith("openjpa.")
                        || key.startsWith("eclipselink.")
                        || key.startsWith("hibernate.")) {
                    String value = WebloggerConfig.getProperty(key);
                    logger.info(key + ": " + value);
                    emfProps.setProperty(key, value);
                }
            }

            if (dbProvider.getType() == DatabaseProvider.ConfigurationType.JNDI_NAME) {
                emfProps.setProperty("jakarta.persistence.nonJtaDataSource", dbProvider.getFullJndiName());
            } else {
                emfProps.setProperty("jakarta.persistence.jdbc.driver", dbProvider.getJdbcDriverClass());
                emfProps.setProperty("jakarta.persistence.jdbc.url", dbProvider.getJdbcConnectionURL());
                emfProps.setProperty("jakarta.persistence.jdbc.user", dbProvider.getJdbcUsername());
                emfProps.setProperty("jakarta.persistence.jdbc.password", dbProvider.getJdbcPassword());
            }

            try {
                ClassLoader classLoader = currentClassLoader();
                PersistenceXmlInfo persistenceXmlInfo = readPersistenceXmlInfo(classLoader);
                PersistenceUnitInfo unitInfo = new RollerPersistenceUnitInfo(
                        persistenceXmlInfo.mappingFileNames(), persistenceXmlInfo.rootUrl(), classLoader);
                this.emf = new org.eclipse.persistence.jpa.PersistenceProvider()
                        .createContainerEntityManagerFactory(unitInfo, emfProps);

            } catch (Exception pe) {
                logger.error("ERROR: creating entity manager", pe);
                throw new WebloggerException(pe);
            }
        }
    }

    /**
     * Build the EclipseLink {@code EntityManagerFactory} without going through
     * {@code Persistence.createEntityManagerFactory}'s classpath-scanning
     * discovery. That entry point asks EclipseLink's {@code
     * PersistenceUnitProcessor} to compute a "persistence unit root URL" from
     * the {@code META-INF/persistence.xml} resource URL, splitting on the
     * <em>last</em> {@code !/} and requiring the left half to end literally in
     * {@code .jar} or {@code .war}. A Spring Boot repackaged executable
     * archive nests {@code WEB-INF/classes/} as its own pseudo-archive
     * boundary, producing a second {@code !/} (e.g. {@code
     * jar:nested:/app.war/!WEB-INF/classes/!/META-INF/persistence.xml}), which
     * fails that check and throws {@code PersistenceUnitLoadingException} even
     * though the resource is perfectly readable. Calling {@code
     * PersistenceProvider.createContainerEntityManagerFactory} directly with a
     * hand-built {@link PersistenceUnitInfo} skips that URL parsing entirely,
     * so this path works identically under {@code java -jar}, {@code
     * spring-boot:run} (exploded classpath), and an external Tomcat.
     *
     * <p>The mapping-file list is parsed from the real {@code
     * META-INF/persistence.xml} at runtime (rather than duplicated as a
     * literal constant here) so that file stays the single source of truth;
     * it never drifts from what this method actually builds.
     *
     * <p>{@link PersistenceUnitInfo#getPersistenceUnitRootUrl()} is not left
     * {@code null}: EclipseLink 5.0.0's {@code
     * PersistenceUnitProcessor.buildPersistenceUnitName} unconditionally calls
     * {@code rootURL.toString()} on it (verified empirically — a null root
     * throws {@code NullPointerException} before any mapping file is even
     * read), so this derives a real, non-null root URL by stripping the
     * trailing {@code META-INF/persistence.xml} off the resource URL used to
     * read the file. Unlike EclipseLink's own discovery-path parsing, nothing
     * in the {@code createContainerEntityManagerFactory} path re-splits this
     * URL on {@code !/} or checks its suffix, so the same nested-archive URL
     * that breaks {@code Persistence.createEntityManagerFactory}'s discovery
     * works fine here purely as an opaque root anchor.
     */
    private static PersistenceXmlInfo readPersistenceXmlInfo(ClassLoader classLoader) throws WebloggerException {
        URL resource = classLoader.getResource(PERSISTENCE_XML_RESOURCE);
        if (resource == null) {
            throw new WebloggerException("Could not find " + PERSISTENCE_XML_RESOURCE + " on the classpath");
        }

        try (InputStream in = resource.openStream()) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Harden against XXE; persistence.xml is a trusted, packaged resource
            // but there is no reason to allow external entity resolution here.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(in);

            Element unit = findPersistenceUnit(doc);
            NodeList mappingFileNodes = unit.getElementsByTagNameNS("*", "mapping-file");
            List<String> mappingFileNames = new ArrayList<>();
            for (int i = 0; i < mappingFileNodes.getLength(); i++) {
                mappingFileNames.add(mappingFileNodes.item(i).getTextContent().trim());
            }
            if (mappingFileNames.isEmpty()) {
                throw new WebloggerException("No <mapping-file> entries found in the \""
                        + PERSISTENCE_UNIT_NAME + "\" persistence-unit of " + PERSISTENCE_XML_RESOURCE);
            }
            return new PersistenceXmlInfo(mappingFileNames, derivePersistenceUnitRootUrl(resource));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new WebloggerException("Could not parse " + PERSISTENCE_XML_RESOURCE, e);
        }
    }

    /**
     * Derives the persistence-unit root URL by stripping the trailing
     * {@code META-INF/persistence.xml} off the resource URL. Used only as an
     * opaque, non-null anchor (see {@link #readPersistenceXmlInfo}); every
     * entity is reached through {@code getMappingFileNames()} instead of
     * being resolved relative to this root.
     */
    private static URL derivePersistenceUnitRootUrl(URL persistenceXmlUrl) throws WebloggerException {
        String urlString = persistenceXmlUrl.toString();
        if (!urlString.endsWith(PERSISTENCE_XML_RESOURCE)) {
            // Defensive fallback: some non-standard classloader returned a URL
            // that doesn't literally end in the resource path we asked for.
            // The exact root doesn't matter (see class doc above), so just use
            // the resource URL itself rather than fail startup over it.
            return persistenceXmlUrl;
        }
        String rootUrlString = urlString.substring(0, urlString.length() - PERSISTENCE_XML_RESOURCE.length());
        try {
            return java.net.URI.create(rootUrlString).toURL();
        } catch (IllegalArgumentException | java.net.MalformedURLException e) {
            throw new WebloggerException("Could not derive a persistence-unit root URL from " + urlString, e);
        }
    }

    /**
     * Holds what {@link #readPersistenceXmlInfo} parses out of the real
     * {@code META-INF/persistence.xml}: the mapping-file list and the
     * persistence-unit root URL derived from the same resource lookup.
     */
    private record PersistenceXmlInfo(List<String> mappingFileNames, URL rootUrl) {
    }

    private static Element findPersistenceUnit(Document doc) throws WebloggerException {
        NodeList persistenceUnits = doc.getElementsByTagNameNS("*", "persistence-unit");
        for (int i = 0; i < persistenceUnits.getLength(); i++) {
            Element candidate = (Element) persistenceUnits.item(i);
            if (PERSISTENCE_UNIT_NAME.equals(candidate.getAttribute("name"))) {
                return candidate;
            }
        }
        throw new WebloggerException("No <persistence-unit name=\"" + PERSISTENCE_UNIT_NAME
                + "\"> found in " + PERSISTENCE_XML_RESOURCE);
    }

    private static ClassLoader currentClassLoader() {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        return tccl != null ? tccl : JPAPersistenceStrategy.class.getClassLoader();
    }

    /**
     * Hand-built {@link PersistenceUnitInfo} standing in for what a Java EE
     * container would normally assemble from {@code META-INF/persistence.xml}.
     * The mapping-file list is the only thing that varies between instances;
     * everything else is a fixed fact about the "RollerPU" unit.
     */
    private static final class RollerPersistenceUnitInfo implements PersistenceUnitInfo {

        private final List<String> mappingFileNames;
        private final URL rootUrl;
        private final ClassLoader classLoader;

        RollerPersistenceUnitInfo(List<String> mappingFileNames, URL rootUrl, ClassLoader classLoader) {
            this.mappingFileNames = mappingFileNames;
            this.rootUrl = rootUrl;
            this.classLoader = classLoader;
        }

        @Override
        public String getPersistenceUnitName() {
            return PERSISTENCE_UNIT_NAME;
        }

        @Override
        public String getPersistenceProviderClassName() {
            return ECLIPSELINK_PROVIDER_CLASS_NAME;
        }

        @Override
        public String getScopeAnnotationName() {
            return null;
        }

        @Override
        public List<String> getQualifierAnnotationNames() {
            return List.of();
        }

        @Override
        public PersistenceUnitTransactionType getTransactionType() {
            return PersistenceUnitTransactionType.RESOURCE_LOCAL;
        }

        @Override
        public DataSource getJtaDataSource() {
            return null;
        }

        @Override
        public DataSource getNonJtaDataSource() {
            // Not RESOURCE_LOCAL JTA lookup: the JDBC/JNDI coordinates are
            // supplied via the standard jakarta.persistence.jdbc.*/
            // jakarta.persistence.nonJtaDataSource properties in the map
            // passed to createContainerEntityManagerFactory, exactly as they
            // were previously passed to Persistence.createEntityManagerFactory.
            return null;
        }

        @Override
        public List<String> getMappingFileNames() {
            return mappingFileNames;
        }

        @Override
        public List<URL> getJarFileUrls() {
            return List.of();
        }

        @Override
        public URL getPersistenceUnitRootUrl() {
            // Must not be null: EclipseLink's buildPersistenceUnitName calls
            // rootURL.toString() on it unconditionally (verified empirically).
            // Every Roller entity's mapping is metadata-complete in its
            // .orm.xml (see the <entity metadata-complete="true"> attribute)
            // and excludeUnlistedClasses() is true below, so this root is
            // never used to scan for annotated classes — it only needs to be
            // non-null, which sidesteps the nested-archive URL *parsing* that
            // breaks Persistence.createEntityManagerFactory's auto-discovery
            // under java -jar (see the class-level javadoc on
            // readPersistenceXmlInfo).
            return rootUrl;
        }

        @Override
        public List<String> getManagedClassNames() {
            return List.of();
        }

        @Override
        public boolean excludeUnlistedClasses() {
            // No <class> elements are declared in persistence.xml and no
            // annotation scanning of the persistence-root is needed; every
            // entity is metadata-complete in its .orm.xml and reached through
            // getMappingFileNames() instead.
            return true;
        }

        @Override
        public SharedCacheMode getSharedCacheMode() {
            return SharedCacheMode.UNSPECIFIED;
        }

        @Override
        public ValidationMode getValidationMode() {
            return ValidationMode.AUTO;
        }

        @Override
        public Properties getProperties() {
            // persistence.xml declares no <properties> block; all EclipseLink/
            // JDBC configuration flows through the map argument to
            // createContainerEntityManagerFactory, same as before.
            return new Properties();
        }

        @Override
        public String getPersistenceXMLSchemaVersion() {
            return "3.0";
        }

        @Override
        public ClassLoader getClassLoader() {
            return classLoader;
        }

        @Override
        public void addTransformer(ClassTransformer transformer) {
            // No bytecode weaving of application classes: RESOURCE_LOCAL
            // outside a Java EE container never invokes the transformer, and
            // every entity is metadata-complete via its .orm.xml, so there is
            // nothing for EclipseLink to weave here anyway.
        }

        @Override
        public ClassLoader getNewTempClassLoader() {
            return classLoader;
        }
    }
    /**
     * Refresh changes to the current object.
     * 
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public void refresh(Object clazz) throws WebloggerException {
        if (clazz == null) {
            return;
        }
        try {
            EntityManager em = getEntityManager(true);
            em.refresh(clazz);
        } catch (Exception e) {
            // ignored;
        }
    }
    /**
     * Flush changes to the datastore, commit transaction, release em.
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public void flush() throws WebloggerException {
        try {
            EntityManager em = getEntityManager(true);
            em.getTransaction().commit();
        } catch (PersistenceException pe) {
            throw new WebloggerException(pe);
        }
    }
    
    /**
     * Release database session, rolls back any uncommitted changes.
     */
    public void release() {
        EntityManager em = null;
        try {
            em = getEntityManager(false);
            if (isTransactionActive(em)) {
                em.getTransaction().rollback();
            }
        } catch (Exception e) {
            logger.error("error during releasing database session", e);
        } finally {
            if (em != null) {
                try {
                    em.close();
                } catch (Exception e) {
                    logger.debug("error during closing EntityManager", e);
                }
            }
            threadLocalEntityManager.remove();
        }
    }
    
    /**
     * Store object using an existing transaction.
     * @param obj the object to persist
     * @return the object persisted
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public Object store(Object obj) throws WebloggerException {
        EntityManager em = getEntityManager(true);
        if (!em.contains(obj)) {
            // If entity is not managed we can assume it is new
            em.persist(obj);
        }
        return obj;
    }
    
    /**
     * Remove object from persistence storage.
     * @param clazz the class of object to remove
     * @param id the id of the object to remove
     * @throws WebloggerException on any error deleting object
     */
    public void remove(Class<?> clazz, String id) throws WebloggerException {
        EntityManager em = getEntityManager(true);
        Object po = em.find(clazz, id);
        em.remove(po);
    }
    
    /**
     * Remove object from persistence storage.
     * @param po the persistent object to remove
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public void remove(Object po) throws WebloggerException {
        EntityManager em = getEntityManager(true);
        em.remove(po);
    }
    
    /**
     * Remove object from persistence storage.
     * @param pos the persistent objects to remove
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public void removeAll(Collection<?> pos) throws WebloggerException {
        EntityManager em = getEntityManager(true);
        for (Object obj : pos) {
            em.remove(obj);
        }
    }
    
    /**
     * Retrieve object, no transaction needed.
     * @param clazz the class of object to retrieve
     * @param id the id of the object to retrieve
     * @return the object retrieved
     * @throws WebloggerException on any error retrieving object
     */
    public Object load(Class<?> clazz, String id) throws WebloggerException {
        EntityManager em = getEntityManager(false);
        return em.find(clazz, id);
    }
    
    /**
     * Return true if a transaction is active on the current EntityManager.
     * @param em the persistence manager
     * @return true if the persistence manager is not null and has an active
     *         transaction
     */
    private boolean isTransactionActive(EntityManager em) {
        return em != null && em.getTransaction().isActive();
    }
    
    /**
     * Get the EntityManager associated with the current thread of control.
     * @param isTransactionRequired true if a transaction is begun if not
     * already active
     * @return the EntityManager
     */
    public EntityManager getEntityManager(boolean isTransactionRequired) {
        EntityManager em = getThreadLocalEntityManager();
        if (isTransactionRequired && !em.getTransaction().isActive()) {
            em.getTransaction().begin();
        }
        return em;
    }
    
    /**
     * Get the current ThreadLocal EntityManager
     */
    private EntityManager getThreadLocalEntityManager() {
        EntityManager em = threadLocalEntityManager.get();
        if (em == null) {
            em = emf.createEntityManager();
            threadLocalEntityManager.set(em);
        }
        return em;
    }
    
    /**
     * Get named query that won't commit changes to DB first (FlushModeType.COMMIT)
     * @param queryName the name of the query
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public Query getNamedQuery(String queryName)
    throws WebloggerException {
        EntityManager em = getEntityManager(false);
        Query q = em.createNamedQuery(queryName);
        // For performance, never flush/commit prior to running queries.
        // Roller code assumes this behavior
        q.setFlushMode(FlushModeType.COMMIT);
        return q;
    }

    /**
     * Get named TypedQuery that won't commit changes to DB first (FlushModeType.COMMIT)
     * Preferred over getNamedQuery(String) due to it being typesafe.
     * @param queryName the name of the query
     * @param resultClass return type of query
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public <T> TypedQuery<T> getNamedQuery(String queryName, Class<T> resultClass)
            throws WebloggerException {
        EntityManager em = getEntityManager(false);
        TypedQuery<T> q = em.createNamedQuery(queryName, resultClass);
        // For performance, never flush/commit prior to running queries.
        // Roller code assumes this behavior
        q.setFlushMode(FlushModeType.COMMIT);
        return q;
    }

    /**
     * Get named query with default flush mode (usually FlushModeType.AUTO)
     * FlushModeType.AUTO commits changes to DB prior to running statement
     *
     * @param queryName the name of the query
     * @param resultClass return type of query
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public <T> TypedQuery<T> getNamedQueryCommitFirst(String queryName, Class<T> resultClass)
            throws WebloggerException {
        EntityManager em = getEntityManager(true);
        return em.createNamedQuery(queryName, resultClass);
    }

    /**
     * Create query from queryString that won't commit changes to DB first (FlushModeType.COMMIT)
     * @param queryString the query
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public Query getDynamicQuery(String queryString)
    throws WebloggerException {
        EntityManager em = getEntityManager(false);
        Query q = em.createQuery(queryString);
        // For performance, never flush/commit prior to running queries.
        // Roller code assumes this behavior
        q.setFlushMode(FlushModeType.COMMIT);
        return q;
    }

    /**
     * Create TypedQuery from queryString that won't commit changes to DB first (FlushModeType.COMMIT)
     * Preferred over getDynamicQuery(String) due to it being typesafe.
     * @param queryString the query
     * @param resultClass return type of query
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public <T> TypedQuery<T> getDynamicQuery(String queryString, Class<T> resultClass)
            throws WebloggerException {
        EntityManager em = getEntityManager(false);
        TypedQuery<T> q = em.createQuery(queryString, resultClass);
        // For performance, never flush/commit prior to running queries.
        // Roller code assumes this behavior
        q.setFlushMode(FlushModeType.COMMIT);
        return q;
    }

    /**
     * Get named update query with default flush mode (usually FlushModeType.AUTO)
     * FlushModeType.AUTO commits changes to DB prior to running statement
     * @param queryName the name of the query
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public Query getNamedUpdate(String queryName)
    throws WebloggerException {
        EntityManager em = getEntityManager(true);
        return em.createNamedQuery(queryName);
    }

    /**
     * Shut down the EntityManagerFactory. Tolerates being called more than
     * once: the {@code Weblogger} bean's {@code destroyMethod="shutdown"} and
     * a caller invoking {@code Weblogger.shutdown()} directly can both reach
     * here for the same instance, and EclipseLink's {@code EntityManagerFactory.close()}
     * throws {@code IllegalStateException} on a second close.
     */
    public void shutdown() {
        if (emf != null && emf.isOpen()) {
            emf.close();
        }
    }
}

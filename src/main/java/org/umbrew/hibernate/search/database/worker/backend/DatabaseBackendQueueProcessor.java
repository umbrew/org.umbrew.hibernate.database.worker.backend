/**
 * The MIT License
 * Copyright (c) 2015 Flemming Harms, Nicky Moelholm
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.umbrew.hibernate.search.database.worker.backend;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.Lock;

import javax.persistence.EntityManager;
import javax.persistence.spi.PersistenceUnitTransactionType;
import javax.transaction.TransactionManager;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.jdbc.dialect.internal.StandardDialectResolver;
import org.hibernate.engine.transaction.jta.platform.internal.AbstractJtaPlatform;
import org.hibernate.engine.transaction.jta.platform.internal.JBossAppServerJtaPlatform;
import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.internal.ParsedPersistenceXmlDescriptor;
import org.hibernate.jpa.internal.EntityManagerFactoryImpl;
import org.hibernate.search.Environment;
import org.hibernate.search.backend.BackendFactory;
import org.hibernate.search.backend.IndexingMonitor;
import org.hibernate.search.backend.LuceneWork;
import org.hibernate.search.backend.OptimizeLuceneWork;
import org.hibernate.search.backend.spi.BackendQueueProcessor;
import org.hibernate.search.indexes.impl.DirectoryBasedIndexManager;
import org.hibernate.search.indexes.serialization.spi.LuceneWorkSerializer;
import org.hibernate.search.spi.WorkerBuildContext;
import org.hibernate.search.util.configuration.impl.MaskedProperty;
import org.hibernate.search.util.logging.impl.Log;
import org.hibernate.search.util.logging.impl.LoggerFactory;
import org.umbrew.hibernate.search.database.worker.backend.DoWithEntityManager.DoWithEntityManagerTask;
import org.umbrew.hibernate.search.database.worker.backend.model.LuceneDatabaseWork;

/**
 * A Hibernate Search {@link BackendQueueProcessor} that persists the work queue into a database.
 * 
 * @author Flemming Harms (flemming.harms@gmail.com)
 * @author Nicky Moelholm (moelholm@gmail.com)
 */
public class DatabaseBackendQueueProcessor implements BackendQueueProcessor {

    // Configuration properties
    public static final String HIBERNATE_PREFIX_FIRST_PART = Environment.WORKER_BACKEND + ".";
    public static final String HIBERNATE_PREFIX = HIBERNATE_PREFIX_FIRST_PART + "hibernate.";
    public static final String HIBERATE_CONNECTION_DATASOURCE = HIBERNATE_PREFIX_FIRST_PART + AvailableSettings.DATASOURCE;
    public static final String HIBERNATE_JTA_PLATFORM = HIBERNATE_PREFIX_FIRST_PART + AvailableSettings.JTA_PLATFORM;

    private static final Log log = LoggerFactory.make();

    private DirectoryBasedIndexManager indexManager;
    private BackendQueueProcessor delegatedBackend;
    private String indexName;

    // Configuration values
    private String jtaPlatform;

    @Override
    public void initialize(Properties props, WorkerBuildContext context, DirectoryBasedIndexManager indexManager) {
        log.debug("Initializing...");

        this.indexManager = indexManager;
        this.indexName = indexManager.getIndexName();
        this.delegatedBackend = BackendFactory.createBackend("lucene", indexManager, context, props);
        this.jtaPlatform = props.getProperty(HIBERNATE_JTA_PLATFORM, JBossAppServerJtaPlatform.class.getName());

        validate(props);

        initializeEntityManagerFactoryHolder((MaskedProperty) props);

        intializeTransactionManagerHolder();

        log.debug("Initialized");
    }

    @Override
    public void close() {
        tearDownEntityManagerFactoryHolder();
        log.debug("Closed");
    }

    @Override
    public void applyWork(List<LuceneWork> workList, IndexingMonitor monitor) {

        if (workList == null) {
            throw new IllegalArgumentException("workList should not be null");
        }

        // This method is called twice - once by the Hibernate Search infrastructure itself
        // and once indirectly by the persistLuceneWorkListToDatabase method below.

        if (shouldIndexNow(workList)) {
            indexLuceneWorkList(workList, monitor);
        } else {
            persistLuceneWorkListToDatabase(workList);
        }
    }

    @Override
    public void applyStreamWork(LuceneWork singleOperation, IndexingMonitor monitor) {
        applyWork(Collections.singletonList(singleOperation), monitor);
    }

    @Override
    public Lock getExclusiveWriteLock() {
        return delegatedBackend.getExclusiveWriteLock();
    }

    @Override
    public void indexMappingChanged() {
        // no-op
    }

    private void validate(Properties props) {
        if (props.getProperty(HIBERATE_CONNECTION_DATASOURCE) == null) {
            throw log.configuratioPropertyCantBeEmpty(HIBERATE_CONNECTION_DATASOURCE);
        }
    }

    private boolean shouldIndexNow(List<LuceneWork> workList) {
        return workList.size() == 1 && workList.get(0) instanceof DatabaseLuceneWorkWrapper;
    }

    private void indexLuceneWorkList(List<LuceneWork> workList, IndexingMonitor monitor) {
        DatabaseLuceneWorkWrapper wrapper = (DatabaseLuceneWorkWrapper) workList.get(0);
        delegatedBackend.applyWork(wrapper.getLuceneWorkList(), monitor);
    }

    private void persistLuceneWorkListToDatabase(List<LuceneWork> workList) {
        DoWithEntityManager.execute(new DoWithEntityManagerTask() {
            @Override
            @SuppressWarnings("unchecked")
            public Void withEntityManager(EntityManager entityManager) {

                List<LuceneWork> filteredQueue = new ArrayList<LuceneWork>(workList);
                for (LuceneWork work : workList) {
                    if (work instanceof OptimizeLuceneWork) {
                        // we don't want optimization to be propagated
                        filteredQueue.remove(work);
                    }
                }

                if (filteredQueue.size() == 0) {
                    return null;
                }

                LuceneWorkSerializer serializer = indexManager.getSerializer();
                byte[] data = serializer.toSerializedModel(filteredQueue);
                LuceneDatabaseWork luceneDatabaseWork = new LuceneDatabaseWork();
                luceneDatabaseWork.setContent(data);
                luceneDatabaseWork.setIndexName(indexName);
                entityManager.persist(luceneDatabaseWork);

                return null;
            }
        });
    }

    private synchronized void intializeTransactionManagerHolder() {
        // TODO Bootstrap the programmatically created JPA EntityManagerFactory so that it has the expected
        // Hibernate services in it's ServiceRegistry
        
        if (TransactionManagerHolder.getTransactionManager() == null) {
            try {
                // Prepare access to the AbstractJtaPlatform's locateTransactionManager() method
                Class<?> jtaPlatformClass = Class.forName(jtaPlatform);
                Method locateTransactionManagerMethod = jtaPlatformClass.getDeclaredMethod("locateTransactionManager");
                locateTransactionManagerMethod.setAccessible(true);

                // Instantiate the current AbstractJtaPlatform...
                AbstractJtaPlatform jtaPlatformInstance = (AbstractJtaPlatform) jtaPlatformClass.newInstance();
                jtaPlatformInstance.injectServices(((EntityManagerFactoryImpl) EntityManagerFactoryHolder.getEntityManagerFactory()).getSessionFactory().getServiceRegistry());

                // ...and use that to obtain the JTA TransactionManager
                TransactionManager jtaTransactionManager = (TransactionManager) locateTransactionManagerMethod.invoke(jtaPlatformInstance);
                TransactionManagerHolder.setTransactionManager(jtaTransactionManager);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private synchronized void tearDownEntityManagerFactoryHolder() {
        if (EntityManagerFactoryHolder.getEntityManagerFactory() != null) {
            EntityManagerFactoryHolder.getEntityManagerFactory().close();
        }
    }

    private synchronized void initializeEntityManagerFactoryHolder(MaskedProperty props) {
        if (EntityManagerFactoryHolder.getEntityManagerFactory() == null) {
            Map<String, String> settings = new HashMap<String, String>();

            for (Object key : props.keySet()) {
                String property = key.toString();
                if (property.startsWith(HIBERNATE_PREFIX)) {
                    String value = props.getProperty(property).toString();
                    property = property.substring(HIBERNATE_PREFIX_FIRST_PART.length());
                    log.debugf("Setting property \"%s\" to \"%s\" for internal entity manager", property, value);
                    settings.put(property, value);
                } else {
                    log.tracef("Ignoring property \"%s\" for internal entity manager", property);
                }
            }

            putIfNotPresent(settings, "hibernate.hbm2ddl.auto", "update");
            putIfNotPresent(settings, "hibernate.show_sql", "false");
            putIfNotPresent(settings, "hibernate.format_sql", "false");
            putIfNotPresent(settings, "hibernate.dialect_resolvers", StandardDialectResolver.class.getName());
            putIfNotPresent(settings, "hibernate.transaction.jta.platform", jtaPlatform);

            ParsedPersistenceXmlDescriptor deploymentDescriptor = new ParsedPersistenceXmlDescriptor(null);
            deploymentDescriptor.addClasses(LuceneDatabaseWork.class.getName());
            deploymentDescriptor.setTransactionType(PersistenceUnitTransactionType.JTA);
            EntityManagerFactoryBuilderImpl builder = new EntityManagerFactoryBuilderImpl(deploymentDescriptor, settings);
            builder.buildServiceRegistry();
            EntityManagerFactoryHolder.setEntityManagerFactory(builder.build());
        }
    }

    private void putIfNotPresent(Map<String, String> settings, String property, String value) {
        if (!settings.containsKey(property)) {
            log.debugf("Setting property \"%s\" to default \"%s\" for internal entity manager", property, value);
            settings.put(property, value);
        }
    }
}

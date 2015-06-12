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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.persistence.EntityManager;
import javax.persistence.spi.PersistenceUnitTransactionType;

import org.hibernate.engine.jdbc.dialect.internal.StandardDialectResolver;
import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.internal.ParsedPersistenceXmlDescriptor;
import org.hibernate.search.Environment;
import org.hibernate.search.backend.BackendFactory;
import org.hibernate.search.backend.IndexingMonitor;
import org.hibernate.search.backend.LuceneWork;
import org.hibernate.search.backend.OptimizeLuceneWork;
import org.hibernate.search.backend.spi.BackendQueueProcessor;
import org.hibernate.search.indexes.impl.DirectoryBasedIndexManager;
import org.hibernate.search.indexes.serialization.spi.LuceneWorkSerializer;
import org.hibernate.search.spi.WorkerBuildContext;
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
    public static final String JTA_PLATFORM = Environment.WORKER_PREFIX + "jta.platform";
    public static final String DATA_SOURCE = Environment.WORKER_PREFIX + "jdbc.datasource";
    public static final String AUTO_DDL = Environment.WORKER_PREFIX + "jdbc.datasource.ddl.auto";
    public static final String SHOW_SQL = Environment.WORKER_PREFIX + "jdbc.sql.show";
    public static final String FORMAT_SQL = Environment.WORKER_PREFIX + "jdbc.sql.format";

    private static final Log log = LoggerFactory.make();

    private DirectoryBasedIndexManager indexManager;
    private BackendQueueProcessor delegatedBackend;
    private String indexName;

    // Configuration values
    private String dataSourceJndiName;
    private String autoDDL;
    private String showSql;
    private String formatSql;
    private String jtaPlatform;

    @Override
    public void initialize(Properties props, WorkerBuildContext context, DirectoryBasedIndexManager indexManager) {
        log.debug("Initializing...");

        this.indexManager = indexManager;
        this.indexName = indexManager.getIndexName();
        this.delegatedBackend = BackendFactory.createBackend("lucene", indexManager, context, props);
        this.dataSourceJndiName = props.getProperty(DATA_SOURCE);
        this.autoDDL = props.getProperty(AUTO_DDL, "create");
        this.showSql = props.getProperty(SHOW_SQL, "false");
        this.formatSql = props.getProperty(FORMAT_SQL, "false");
        this.jtaPlatform = props.getProperty(JTA_PLATFORM, "org.hibernate.service.jta.platform.internal.JBossAppServerJtaPlatform");

        logConfiguration();

        validate();

        initializeEntityManagerFactory();

        log.debug("Initialized");
    }

    @Override
    public void close() {
        EntityManagerFactoryHolder.getEntityManagerFactory().close();
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
        log.warnSuspiciousBackendDirectoryCombination(indexName);
        return new ReentrantLock(); // keep the invoker happy, still it's useless
    }

    @Override
    public void indexMappingChanged() {
        // no-op
    }

    private void logConfiguration() {
        if (log.isDebugEnabled()) {
            log.debug(">> Configuration");
            logProperty(JTA_PLATFORM, jtaPlatform);
            logProperty(DATA_SOURCE, dataSourceJndiName);
            logProperty(AUTO_DDL, autoDDL);
            logProperty(SHOW_SQL, showSql);
            logProperty(FORMAT_SQL, formatSql);
            log.debug("<< Configuration");
        }
    }

    private void logProperty(String propertyName, String propertyValue) {
        log.debug(String.format("  Property %-35s => [%s]", String.format("[%s]", propertyName), propertyValue));
    }

    private void validate() {
        if (this.dataSourceJndiName == null) {
            throw log.configuratioPropertyCantBeEmpty(DATA_SOURCE);
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

    private synchronized void initializeEntityManagerFactory() {

        if (EntityManagerFactoryHolder.getEntityManagerFactory() == null) {
            Map<String, String> settings = new HashMap<String, String>();

            settings.put("hibernate.connection.datasource", this.dataSourceJndiName);
            settings.put("hibernate.hbm2ddl.auto", this.autoDDL);
            settings.put("hibernate.show_sql", showSql);
            settings.put("hibernate.format_sql", formatSql);
            settings.put("hibernate.dialect_resolvers", StandardDialectResolver.class.getName());
            settings.put("hibernate.transaction.jta.platform", jtaPlatform);

            ParsedPersistenceXmlDescriptor deploymentDescriptor = new ParsedPersistenceXmlDescriptor(null);
            deploymentDescriptor.addClasses(LuceneDatabaseWork.class.getName());
            deploymentDescriptor.setTransactionType(PersistenceUnitTransactionType.JTA);
            ClassLoader classLoader = this.getClass().getClassLoader();
            EntityManagerFactoryBuilderImpl builder = new EntityManagerFactoryBuilderImpl(deploymentDescriptor, settings, classLoader);
            EntityManagerFactoryHolder.setEntityManagerFactory(builder.build());
        }

    }

}

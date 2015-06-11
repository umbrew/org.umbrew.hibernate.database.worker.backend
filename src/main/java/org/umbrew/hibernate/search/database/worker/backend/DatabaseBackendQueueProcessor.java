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
import org.hibernate.search.backend.spi.BackendQueueProcessor;
import org.hibernate.search.indexes.impl.DirectoryBasedIndexManager;
import org.hibernate.search.spi.WorkerBuildContext;
import org.hibernate.search.util.logging.impl.Log;
import org.hibernate.search.util.logging.impl.LoggerFactory;
import org.umbrew.hibernate.search.database.worker.backend.DoWithEntityManager.DoWithEntityManagerTask;
import org.umbrew.hibernate.search.database.worker.backend.model.LuceneDatabaseWork;

/**
 * @author fharms
 * @author moelholm
 */
public class DatabaseBackendQueueProcessor implements BackendQueueProcessor {

    public static final String DATA_SOURCE = Environment.WORKER_PREFIX + "jdbc.datasource";
    public static final String AUTO_DDL = Environment.WORKER_PREFIX + "jdbc.datasource.ddl.auto";

    private static final Log log = LoggerFactory.make();

    private String dataSourceJndiName;
    private DirectoryBasedIndexManager indexManager;
    private String indexName;
    private String autoDDL;
    private BackendQueueProcessor delegatedBackend;

    @Override
    public void initialize(Properties props, WorkerBuildContext context, DirectoryBasedIndexManager indexManager) {
        log.debug("Initializing...");

        this.indexManager = indexManager;
        this.indexName = indexManager.getIndexName();
        this.dataSourceJndiName = props.getProperty(DATA_SOURCE);
        this.autoDDL = props.getProperty(AUTO_DDL, "create");

        if (log.isDebugEnabled()) {
            log.debug(String.format("Using datasource [%s]", this.dataSourceJndiName));
            log.debug(String.format("Using DDL automode [%s]", this.autoDDL));
        }

        validate();

        delegatedBackend = BackendFactory.createBackend("lucene", indexManager, context, props);

        initializeEntityManagerFactory();

        log.debug("Initialized");
    }

    private void validate() {
        if (this.dataSourceJndiName == null) {
            throw log.configuratioPropertyCantBeEmpty(DATA_SOURCE);
        }

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

        if (workList.size() == 1 && workList.get(0) instanceof DatabaseLuceneWorkWrapper) {
            DatabaseLuceneWorkWrapper wrapper = (DatabaseLuceneWorkWrapper) workList.get(0);
            delegatedBackend.applyWork(wrapper.getLuceneWorkList(), monitor);
        } else {
            DoWithEntityManager.execute(new DoWithEntityManagerTask() {
                @Override
                @SuppressWarnings("unchecked")
                public Void withEntityManager(EntityManager entityManager) {
                    DatabaseBackendQueueTask databaseBackendQueueTask = new DatabaseBackendQueueTask(indexName, workList,
                            indexManager, entityManager);
                    databaseBackendQueueTask.run();
                    return null;
                }
            });
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

    private synchronized void initializeEntityManagerFactory() {

        if (EntityManagerFactoryHolder.getEntityManagerFactory() == null) {
            Map<String, String> settings = new HashMap<String, String>();

            settings.put("hibernate.connection.datasource", this.dataSourceJndiName);
            settings.put("hibernate.hbm2ddl.auto", this.autoDDL);
            settings.put("hibernate.show_sql", "true");
            settings.put("hibernate.format_sql", "true");
            settings.put("hibernate.dialect_resolvers", StandardDialectResolver.class.getName());
            settings.put("hibernate.transaction.jta.platform",
                    "org.hibernate.service.jta.platform.internal.JBossAppServerJtaPlatform");

            ParsedPersistenceXmlDescriptor deploymentDescriptor = new ParsedPersistenceXmlDescriptor(null);
            deploymentDescriptor.addClasses(LuceneDatabaseWork.class.getName());
            deploymentDescriptor.setTransactionType(PersistenceUnitTransactionType.JTA);
            ClassLoader classLoader = this.getClass().getClassLoader();
            EntityManagerFactoryBuilderImpl builder = new EntityManagerFactoryBuilderImpl(deploymentDescriptor, settings,
                    classLoader);
            EntityManagerFactoryHolder.setEntityManagerFactory(builder.build());
        }

    }

}

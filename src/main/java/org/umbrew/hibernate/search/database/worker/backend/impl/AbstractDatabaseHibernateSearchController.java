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
/**
 * @author fharms
 * @author moelholm
 */
package org.umbrew.hibernate.search.database.worker.backend.impl;

import java.util.Collections;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;

import org.hibernate.Session;
import org.hibernate.search.backend.LuceneWork;
import org.hibernate.search.engine.spi.SearchFactoryImplementor;
import org.hibernate.search.indexes.impl.IndexManagerHolder;
import org.hibernate.search.indexes.spi.IndexManager;
import org.hibernate.search.util.impl.ContextHelper;
import org.hibernate.search.util.logging.impl.Log;
import org.hibernate.search.util.logging.impl.LoggerFactory;
import org.umbrew.hibernate.search.database.worker.backend.DatabaseLuceneWorkWrapper;
import org.umbrew.hibernate.search.database.worker.backend.DoWithEntityManager;
import org.umbrew.hibernate.search.database.worker.backend.DoWithEntityManager.DoWithEntityManagerTask;
import org.umbrew.hibernate.search.database.worker.backend.model.LuceneDatabaseWork;

/**
 * @author fharms
 * @author moelhom 
 *
 */
public abstract class AbstractDatabaseHibernateSearchController {

    private static final Log log = LoggerFactory.make();

    private int luceneWorkBatchSize = 100;

    /**
     * Return the current or give a new session This session is not used per se, but is the link to access the Search configuration.
     * <p>
     * A typical EJB 3.0 usecase would be to get the session from the container (injected) eg in JBoss EJB 3.0
     * <p>
     * <code>
     * &#64;PersistenceContext private Session session;<br>
     * <br>
     * protected Session getSession() {<br>
     * &nbsp; &nbsp;return session<br>
     * }<br>
     * </code>
     * <p>
     * eg in any container<br>
     * <code>
     * &#64;PersistenceContext private EntityManager entityManager;<br>
     * <br>
     * protected Session getSession() {<br>
     * &nbsp; &nbsp;return (Session) entityManager.getdelegate();<br>
     * }<br>
     * </code>
     */
    protected abstract Session getSession();

    /**
     * The session should normally only be closed if it has not been directly or indirectly injected by a container.
     */
    protected void cleanSessionIfNeeded(Session session) {

    }

    /**
     * Set the number of {@link LuceneDatabaseWork} is should process in one transaction
     * <br>
     * <p>
     * Default is 100
     * </p>
     */
    protected void setLuceneWorkBatchSize(int size) {
        this.luceneWorkBatchSize = size;
    }

    /**
     */
    @SuppressWarnings("deprecation")
    public void processWorkQueue() {

        final Session session = getSession();
        final SearchFactoryImplementor factory = ContextHelper.getSearchFactory(session);
        IndexManagerHolder indexManagerHolder = factory.getIndexManagerHolder();
        try {

            DoWithEntityManager.execute(new DoWithEntityManagerTask() {
                @Override
                @SuppressWarnings("unchecked")
                public Void withEntityManager(EntityManager entityManager) {
                    log.debug("Work queue processing started");

                    String queryAsString = String.format("from %s order by id asc", LuceneDatabaseWork.class.getName());
                    TypedQuery<LuceneDatabaseWork> query = entityManager.createQuery(queryAsString, LuceneDatabaseWork.class);
                    query.setMaxResults(luceneWorkBatchSize);
                    List<LuceneDatabaseWork> resultList = query.getResultList();
                    log.debug(String.format("Found [%s] %ss", resultList.size(), LuceneDatabaseWork.class.getSimpleName()));

                    for (LuceneDatabaseWork luceneWork : resultList) {
                        String indexName = luceneWork.getIndexName();
                        IndexManager indexManager = indexManagerHolder.getIndexManager(indexName);
                        if (indexManager == null) {
                            log.messageReceivedForUndefinedIndex(indexName);
                            continue;
                        }
                        log.debug(String.format("Indexing [%s] [id=%s]", indexName, luceneWork.getId()));
                        List<LuceneWork> queue = indexManager.getSerializer().toLuceneWorks(luceneWork.getContent());
                        indexManager.performOperations(Collections.singletonList(new DatabaseLuceneWorkWrapper(queue)), null);
                        entityManager.remove(luceneWork);
                    }

                    log.debug("Work queue processing finished");
                    return null;
                }

            });

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            cleanSessionIfNeeded(session);
        }
    }

}

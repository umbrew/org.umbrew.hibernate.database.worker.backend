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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;

import java.util.List;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.transaction.UserTransaction;

import org.hibernate.Session;
import org.hibernate.search.jpa.FullTextEntityManager;
import org.hibernate.search.jpa.FullTextQuery;
import org.hibernate.search.jpa.Search;
import org.hibernate.search.query.dsl.QueryBuilder;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.umbrew.hibernate.search.database.worker.backend.impl.AbstractDatabaseHibernateSearchController;
import org.umbrew.hibernate.search.database.worker.backend.model.LuceneDatabaseWork;
import org.umbrew.model.Message;

/**
 * @author Flemming Harms (flemming.harms@gmail.com)
 * @author Nicky Moelholm (moelholm@gmail.com)
 */
@RunWith(Arquillian.class)
public class DatabaseBackendQueueProcessorIT {

    @PersistenceContext(name = "hibernate.search.database.worker.backend-persistence-unit")
    private EntityManager entityManager;

    @Inject
    private UserTransaction userTransaction;

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class).addClass(DatabaseBackendQueueProcessor.class).addClass(Message.class).addClass(LuceneDatabaseWork.class)
                .addPackage(DatabaseBackendQueueProcessor.class.getPackage()).addClass(AbstractDatabaseHibernateSearchController.class)
                .addAsResource("persistence.xml", "META-INF/persistence.xml").addAsWebInfResource("jboss-deployment-structure.xml", "jboss-deployment-structure.xml")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Test
    public void testIndexing_whenNewIndexedEntityIsSaved_thenEntityIsIndexed() throws Exception {

        // Given
        insertMessageEntity("hello world");
        assertEquals(1, countLuceneDatabaseWorkEntities());

        // When
        indexRightNow();
        List<Message> allIndexedMessages = findIndexedMessages();

        // Then
        assertEquals(1, allIndexedMessages.size());
        assertEquals("hello world", allIndexedMessages.iterator().next().getContent());
    }

    @Test
    public void testIndexing_whenNewIndexedEntityIsSavedAndThenUpdated_thenAllEntityChangesAreIndexed() throws Exception {

        // Given
        insertMessageEntity("hello world");
        updateMessageEntity("updated hello world");
        assertEquals(2, countLuceneDatabaseWorkEntities());

        // When
        indexRightNow();
        List<Message> allIndexedMessages = findIndexedMessages();

        // Then
        assertEquals(1, allIndexedMessages.size());
        assertEquals("updated hello world", allIndexedMessages.iterator().next().getContent());
        assertEquals(0, countLuceneDatabaseWorkEntities());
    }

    @Before
    public void tearDown() throws Exception {
        deleteAllMessageEntities();
        indexRightNow();
        assertNoLuceneDatabaseWorkItemsInDatabase();
    }

    @SuppressWarnings("unchecked")
    private List<Message> findIndexedMessages() {
        FullTextEntityManager ft = Search.getFullTextEntityManager(entityManager);
        QueryBuilder qb = ft.getSearchFactory().buildQueryBuilder().forEntity(Message.class).get();
        org.apache.lucene.search.Query query = qb.keyword().onFields("content").matching("hello world").createQuery();
        FullTextQuery fullTextQuery = ft.createFullTextQuery(query, Message.class);
        return fullTextQuery.getResultList();
    }

    private int countLuceneDatabaseWorkEntities() throws Exception {
        String query = String.format("from %s", LuceneDatabaseWork.class.getName());
        @SuppressWarnings("unchecked")
        List<LuceneDatabaseWork> databaseWorkItems = entityManager.createQuery(query).getResultList();
        return databaseWorkItems.size();
    }

    private void insertMessageEntity(String content) throws Exception {
        doInTransaction(new Runnable() {
            @Override
            public void run() {
                Message helloWorldMessage = createAndPersistNewMessage(content);
                Message helloWorldMessageFromDatabase = entityManager.find(Message.class, helloWorldMessage.getId());
                assertNotNull(helloWorldMessageFromDatabase);
                assertNotSame(helloWorldMessage, helloWorldMessageFromDatabase);
            }
        });
    }

    private void updateMessageEntity(String content) throws Exception {
        doInTransaction(new Runnable() {
            @Override
            public void run() {
                TypedQuery<Message> query = entityManager.createQuery(String.format("from %s", Message.class.getName(), Message.class), Message.class);
                query.getSingleResult().setContent(content);
            }
        });
    }

    private void deleteAllMessageEntities() throws Exception {
        doInTransaction(new Runnable() {
            @Override
            public void run() {
                String sql = String.format("delete from %s", Message.class.getName());
                Query query = entityManager.createQuery(sql);
                query.executeUpdate();
            }
        });
        assertNoLuceneDatabaseWorkItemsInDatabase();
    }

    private void assertNoLuceneDatabaseWorkItemsInDatabase() {
        String query = String.format("from %s", LuceneDatabaseWork.class.getName());
        @SuppressWarnings("unchecked")
        List<LuceneDatabaseWork> databaseWorkItemsBefore = entityManager.createQuery(query).getResultList();
        assertEquals(0, databaseWorkItemsBefore.size());
    }

    private void indexRightNow() throws Exception {
        doInTransaction(new Runnable() {
            @Override
            public void run() {
                AbstractDatabaseHibernateSearchController abstractDatabaseHibernateSearchController = new AbstractDatabaseHibernateSearchController() {
                    @Override
                    protected Session getSession() {
                        return (Session) entityManager.getDelegate();
                    }

                    @Override
                    protected void cleanSessionIfNeeded(Session session) {
                        session.close();
                    }
                };

                abstractDatabaseHibernateSearchController.processWorkQueue();
            }
        });
    }

    private Message createAndPersistNewMessage(String messageContent) {
        Message helloWorldMessage = new Message();
        helloWorldMessage.setContent(messageContent);
        entityManager.persist(helloWorldMessage);
        entityManager.flush();
        entityManager.clear();
        assertNotNull(helloWorldMessage.getId());
        return helloWorldMessage;
    }

    private void doInTransaction(Runnable runnable) {
        try {
            userTransaction.begin();
            runnable.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                userTransaction.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}

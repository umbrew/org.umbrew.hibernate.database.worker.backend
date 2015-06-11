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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.umbrew.hibernate.search.database.worker.backend.impl.AbstractDatabaseHibernateSearchController;
import org.umbrew.hibernate.search.database.worker.backend.model.LuceneDatabaseWork;
import org.umbrew.model.Message;

/**
 * 
 * @author fharms
 * @author moelholm
 *
 *
 */
@RunWith(Arquillian.class)
public class DatabaseBackendQueueProcessorIT {

    @PersistenceContext(name = "hibernate.search.database.worker.backend-persistence-unit")
    private EntityManager entityManager;

    @Inject
    private UserTransaction userTransaction;

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class).addClass(DatabaseBackendQueueProcessor.class).addClass(Message.class)
                .addClass(LuceneDatabaseWork.class).addPackage(DatabaseBackendQueueProcessor.class.getPackage())
                .addClass(AbstractDatabaseHibernateSearchController.class)
                .addAsResource("META-INF/persistence.xml", "META-INF/persistence.xml")
                .addAsWebInfResource("META-INF/jboss-deployment-structure.xml", "jboss-deployment-structure.xml")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Test
    public void testDatabaseWorkerBackend() throws Exception {

        // Given
        insertOneMessageEntity();
        assertEquals(1, countLuceneDatabaseWorkEntities());

        // When
        performIndexing();
        List<Message> allIndexedMessages = findIndexedMessages();

        // Then
        assertEquals(1, allIndexedMessages.size());
        assertEquals("hello world", allIndexedMessages.iterator().next().getContent());
        assertEquals(0, countLuceneDatabaseWorkEntities());
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

    private void insertOneMessageEntity() throws Exception {
        userTransaction.begin();
        assertNoLuceneDatabaseWorkItemsInDatabase();

        Message helloWorldMessage = createAndPersistNewMessage("hello world");
        Message helloWorldMessageFromDatabase = entityManager.find(Message.class, helloWorldMessage.getId());
        assertNotNull(helloWorldMessageFromDatabase);
        assertNotSame(helloWorldMessage, helloWorldMessageFromDatabase);

        userTransaction.commit();
    }

    private void assertNoLuceneDatabaseWorkItemsInDatabase() {
        String query = String.format("from %s", LuceneDatabaseWork.class.getName());
        List<LuceneDatabaseWork> databaseWorkItemsBefore = entityManager.createQuery(query).getResultList();
        assertEquals(0, databaseWorkItemsBefore.size());
    }

    private void performIndexing() throws Exception {
        System.out.println("kermitter nu");
        userTransaction.begin();

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
        userTransaction.commit();
        System.out.println("så er der sgu committed, ik?");
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
}

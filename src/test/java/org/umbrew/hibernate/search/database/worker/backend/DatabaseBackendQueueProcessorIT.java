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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.transaction.UserTransaction;

import org.hibernate.engine.jdbc.dialect.internal.StandardDialectResolver;
import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.internal.ParsedPersistenceXmlDescriptor;
import org.hibernate.search.jpa.FullTextEntityManager;
import org.hibernate.search.jpa.FullTextQuery;
import org.hibernate.search.jpa.Search;
import org.hibernate.search.query.dsl.QueryBuilder;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.umbrew.hibernate.search.database.worker.backend.model.LuceneDatabaseWork;
import org.umbrew.model.Message;

@RunWith(Arquillian.class)
public class DatabaseBackendQueueProcessorIT {

    @Inject
    private DatabaseBackendQueueProcessor databasebackendqueueprocessor;

    @PersistenceContext(name = "hibernate.search.database.worker.backend-persistence-unit")
    private EntityManager entityManager;

    @Inject
    UserTransaction userTransaction;

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class).addClass(DatabaseBackendQueueProcessor.class).addClass(Message.class)
                .addClass(LuceneDatabaseWork.class).addPackage(DatabaseBackendQueueProcessor.class.getPackage())
                .addAsResource("META-INF/persistence.xml", "META-INF/persistence.xml")
                .addAsWebInfResource("META-INF/jboss-deployment-structure.xml", "jboss-deployment-structure.xml")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }
    
    @Test
    public void kat_slettes_af_hund() {

        Map<String, String> settings = new HashMap<String, String>();

        settings.put("hibernate.connection.datasource", "java:jboss/datasources/ExampleDS");
        settings.put("hibernate.hbm2ddl.auto", "create");
        settings.put("hibernate.dialect_resolvers", StandardDialectResolver.class.getName());

        ParsedPersistenceXmlDescriptor deploymentDescriptor = new ParsedPersistenceXmlDescriptor(null);
        deploymentDescriptor.addClasses(LuceneDatabaseWork.class.getName());

        ClassLoader classLoader = this.getClass().getClassLoader();
        EntityManagerFactoryBuilderImpl builder = new EntityManagerFactoryBuilderImpl(deploymentDescriptor, settings,
                classLoader);
        EntityManagerFactory entityManagerFactory = builder.build();

        EntityManager entityManager = entityManagerFactory.createEntityManager();

        EntityTransaction tx = entityManager.getTransaction();

        try {
            tx.begin();

            LuceneDatabaseWork luceneDatabaseWork = new LuceneDatabaseWork();
            luceneDatabaseWork.setContent(new byte[0]);
            luceneDatabaseWork.setIndexName("kalv");

            entityManager.persist(luceneDatabaseWork);

            System.out.println(">> wooHOOO");

        } catch (Exception e) {
            e.printStackTrace();

        } finally {
            tx.commit();

        }
    }


    @Test
    public void should_be_deployed() {
        Assert.assertNotNull(databasebackendqueueprocessor);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void entity_can_be_saved_and_read() throws Exception {
        userTransaction.begin();
        
        List<LuceneDatabaseWork> databaseWorkItemsBefore = entityManager.createQuery(String.format("from %s", LuceneDatabaseWork.class.getName())).getResultList();
        assertEquals(0, databaseWorkItemsBefore.size());
        
        Message helloWorldMessage = createAndPersistNewMessage("hello world");
        Message helloWorldMessageFromDatabase = entityManager.find(Message.class, helloWorldMessage.getId());
        assertNotNull(helloWorldMessageFromDatabase);
        assertNotSame(helloWorldMessage, helloWorldMessageFromDatabase);
        
        userTransaction.commit();
System.out.println("ko");
        userTransaction.begin();
System.out.println("tyr");        
        List<LuceneDatabaseWork> databaseWorkItemsAfter = entityManager.createQuery(String.format("from %s", LuceneDatabaseWork.class.getName())).getResultList();
System.out.println("Gris");        
        assertEquals(1, databaseWorkItemsAfter.size());        
        
        FullTextEntityManager ft = Search.getFullTextEntityManager(entityManager);
        QueryBuilder qb = ft.getSearchFactory().buildQueryBuilder().forEntity(Message.class).get();
        org.apache.lucene.search.Query query = qb.keyword().onFields("content").matching("hello world").createQuery();
        // wrap Lucene query in a org.hibernate.Query
        FullTextQuery fullTextQuery = ft.createFullTextQuery(query, Message.class);

        // execute search
        @SuppressWarnings("unchecked")
        List<Message> result = fullTextQuery.getResultList();
        Assert.assertEquals(1, result.size());
        Assert.assertEquals("hello world", ((Message) result.get(0)).getContent());
        userTransaction.commit();

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

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

import javax.persistence.EntityManager;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.hibernate.search.util.logging.impl.Log;
import org.hibernate.search.util.logging.impl.LoggerFactory;

/**
 * @author Flemming Harms (flemming.harms@gmail.com)
 * @author Nicky Moelholm (moelholm@gmail.com)
 */
public class DoWithEntityManager {

    private static final Log log = LoggerFactory.make();

    public static <T> T execute(DoWithEntityManagerTask task) {
        TransactionManager transactionManager = null;
        Transaction activeJtaTransaction = null;

        EntityManager entityManager = null;
        try {
            transactionManager = TransactionManagerHolder.getTransactionManager();
            activeJtaTransaction = transactionManager.getTransaction();

            if (activeJtaTransaction != null) {
                log.debug("Suspending existing JTA transaction");
                transactionManager.suspend();
            }
            transactionManager.begin();
            entityManager = EntityManagerFactoryHolder.getEntityManagerFactory().createEntityManager();
            entityManager.joinTransaction();
            
            T result = task.withEntityManager(entityManager);

            transactionManager.commit();
            return result;
        } catch (Exception e) {
            try {
                transactionManager.rollback();
            } catch (Exception e1) {
                log.error(e1);
            }
            log.error(e);
            throw new RuntimeException(e);

        } finally {
            entityManager.close();
            if (activeJtaTransaction != null) {
                try {
                    log.debug("Resuming existing JTA transaction");
                    transactionManager.resume(activeJtaTransaction);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static interface DoWithEntityManagerTask {
        <T> T withEntityManager(EntityManager entityManager);
    }

}
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
import java.util.List;

import javax.persistence.EntityManager;

import org.hibernate.search.backend.LuceneWork;
import org.hibernate.search.exception.ErrorContext;
import org.hibernate.search.exception.ErrorHandler;
import org.hibernate.search.indexes.serialization.avro.impl.AvroSerializationProvider;
import org.hibernate.search.indexes.serialization.impl.PluggableSerializationLuceneWorkSerializer;
import org.hibernate.search.util.logging.impl.Log;
import org.hibernate.search.util.logging.impl.LoggerFactory;
import org.umbrew.hibernate.search.database.worker.backend.DoWithEntityManager.DoWithEntityManagerTask;
import org.umbrew.hibernate.search.database.worker.backend.model.LuceneDatabaseWork;

/**
 * @author Flemming Harms (flemming.harms@gmail.com)
 * @author Nicky Moelholm (moelholm@gmail.com)
 */
public class HibernateSearchErrorHandler implements ErrorHandler {

    private static final Log LOG = LoggerFactory.make();

    @Override
    public void handle(ErrorContext context) {

        List<LuceneWork> luceneWorkToBeRetried = extractLuceneWorkThatShouldBeRetried(context);

        LOG.warn(String.format("Hibernate Search failed to index [%s] %ss. Caused by: [%s]", luceneWorkToBeRetried.size(), LuceneWork.class.getSimpleName(), context.getThrowable()));

        PluggableSerializationLuceneWorkSerializer serializer = new PluggableSerializationLuceneWorkSerializer(new AvroSerializationProvider(), null);

        DoWithEntityManager.execute(new DoWithEntityManagerTask() {
            @Override
            @SuppressWarnings("unchecked")
            public Void withEntityManager(EntityManager entityManager) {
                byte[] data = serializer.toSerializedModel(luceneWorkToBeRetried);
                LuceneDatabaseWork luceneDatabaseWork = new LuceneDatabaseWork();
                luceneDatabaseWork.setContent(data);
                luceneDatabaseWork.setIndexName(luceneWorkToBeRetried.iterator().next().getEntityClass().getName());
                entityManager.persist(luceneDatabaseWork);
                return null;
            }
        });
    }

    /**
     * Suited to handle a single Exception, where no ErrorContext is needed.
     * 
     * @since 4.0
     * @param errorMsg any description which could be useful to identify what was happening
     * @param exception the error to be handled
     */
    @Override
    public void handleException(String errorMsg, Throwable exception) {
        LOG.warn(String.format("Hibernate Search exploded :(. Message: [%s]. Caused by: [%s]", errorMsg, exception));
    }

    private List<LuceneWork> extractLuceneWorkThatShouldBeRetried(ErrorContext context) {
        List<LuceneWork> luceneWorkToBeRetried = new ArrayList<>();
        luceneWorkToBeRetried.add(context.getOperationAtFault());
        luceneWorkToBeRetried.addAll(context.getFailingOperations());
        return luceneWorkToBeRetried;
    }
}

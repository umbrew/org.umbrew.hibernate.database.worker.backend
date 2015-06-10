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
import org.hibernate.search.backend.OptimizeLuceneWork;
import org.hibernate.search.indexes.impl.DirectoryBasedIndexManager;
import org.hibernate.search.indexes.serialization.spi.LuceneWorkSerializer;
import org.umbrew.hibernate.search.database.worker.backend.model.LuceneDatabaseWork;

public class DatabaseBackendQueueTask implements Runnable {

    private final String indexName;
    private final DirectoryBasedIndexManager indexManager;
    private final EntityManager entityManager;
    private List<LuceneWork> workList;

    public DatabaseBackendQueueTask(String indexName, List<LuceneWork> workList, DirectoryBasedIndexManager indexManager,
            EntityManager entityManager) {
        this.indexName = indexName;
        this.workList = workList;
        this.indexManager = indexManager;
        this.entityManager = entityManager;
    }

    @Override
    public void run() {
        List<LuceneWork> filteredQueue = new ArrayList<LuceneWork>(workList);
        for (LuceneWork work : workList) {
            if (work instanceof OptimizeLuceneWork) {
                // we don't want optimization to be propagated
                filteredQueue.remove(work);
            }
        }
        if (filteredQueue.size() == 0) {
            return;
        }
        LuceneWorkSerializer serializer = indexManager.getSerializer();
        byte[] data = serializer.toSerializedModel(filteredQueue);
        LuceneDatabaseWork luceneDatabaseWork = new LuceneDatabaseWork();
        luceneDatabaseWork.setContent(data);
        luceneDatabaseWork.setIndexName(indexName);
        entityManager.joinTransaction();;
        try {
            entityManager.persist(luceneDatabaseWork);
        } catch (Exception e) { 
            e.printStackTrace();
        } finally {
        }

    }

}

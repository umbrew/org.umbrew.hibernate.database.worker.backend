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

import java.util.List;

import org.hibernate.search.backend.LuceneWork;
import org.hibernate.search.backend.impl.WorkVisitor;
import org.umbrew.hibernate.search.database.worker.backend.impl.AbstractDatabaseHibernateSearchController;

/**
 * Wrap a {@link LuceneWork} to distinguish between when it's processed by {@link AbstractDatabaseHibernateSearchController} and {@link DatabaseBackendQueueProcessor}
 * 
 * @author Flemming Harms (flemming.harms@gmail.com)
 * @author Nicky Moelholm (moelholm@gmail.com)
 */
public class DatabaseLuceneWorkWrapper extends LuceneWork {

    private static final long serialVersionUID = 0xCAFEBABE;

    private final List<LuceneWork> luceneWorkList;

    public DatabaseLuceneWorkWrapper(List<LuceneWork> luceneWorkList) {
        super(new Integer(1), "1", DatabaseLuceneWorkWrapper.class);
        this.luceneWorkList = luceneWorkList;
    }

    @Override
    public <T> T getWorkDelegate(WorkVisitor<T> visitor) {
        return null;
    }

    public List<LuceneWork> getLuceneWorkList() {
        return this.luceneWorkList;
    }
}

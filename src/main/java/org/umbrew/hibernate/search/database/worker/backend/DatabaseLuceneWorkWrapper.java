package org.umbrew.hibernate.search.database.worker.backend;

import org.hibernate.search.backend.LuceneWork;
import org.hibernate.search.backend.impl.WorkVisitor;

public class DatabaseLuceneWorkWrapper extends LuceneWork {

    private static final long serialVersionUID = 0xCAFEBAE;
    
    final private LuceneWork luceneWork;
    
    public DatabaseLuceneWorkWrapper(LuceneWork luceneWork) {
        super(luceneWork.getId(),luceneWork.getIdInString(),luceneWork.getEntityClass(), luceneWork.getDocument());
        this.luceneWork = luceneWork;
    }
    
    @Override
    public <T> T getWorkDelegate(WorkVisitor<T> visitor) {
        return this.luceneWork.getWorkDelegate(visitor);
    }

    public LuceneWork getOriginal() {
        return this.luceneWork;
    }
}

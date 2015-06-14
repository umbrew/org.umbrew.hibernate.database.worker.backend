# Hibernate Search Database back end worker

This is an alternative back end processor to the current supported "Lucene","JMS" and "JGROUPS" that will store the LuceneWork'ers in a database for later processing by a scheduled job. 
The advantage with the database worker is all workers are stored in a database, and in case of a node is crashing nothing is lost, second the database worker dosen't need to know who is the master or slave.

The only requirement is that you have to define a job that only run one place in the cluster, but this can be done with Quartz or a [Wildfly HA singleton](https://github.com/wildfly/quickstart/tree/master/cluster-ha-singleton) and possible other ways.

The backend will both work in single node and clustered environment

> Only tested with Wildfly 8.2 and Hibernate Search 4.5.1

## How do I install it.

That's easy 

* Download the jar file from [releases](https://github.com/umbrew/org.umbrew.hibernate.database.worker.backend/releases) or checkout
the code and build it your self.
* Place the jar file in the EAR or WAR archive.
* Update the persistence.xml with following configuration

> &#60;property name="hibernate.search.default.worker.backend" value="org.umbrew.hibernate.search.database.worker.backend.DatabaseBackendQueueProcessor"/&#62;

### Create a Job for processing Lucene workers
You will have to do a little work for your self, and that is to create a job or HA singleton that is only running on a single node each time. 

The job has to extend this class

>AbstractDatabaseHibernateSearchController

and let it call the method

>processWorkQueue()

It could look like this

```java
@Singleton
@Startup
@LocalBean
@ConcurrencyManagement(BEAN)
public class DatabaseHibernateSearchController extends AbstractDatabaseHibernateSearchController {

    @PersistenceContext(name = "my-databasesource") //point to the database where the LuceneWork is stored
    private Session session;

    @Override
    protected void cleanSessionIfNeeded(Session arg0) {
    }

    @Override
    protected Session getSession() {
        return session;
    }

    @PostConstruct
    public void start() {
        // Schedule your Quartz job here and let it call the processWorkQueue() method
    }

}
``` 

## How do I build it.

Easy again, just type the following command

> mvn clean install -P wildfly
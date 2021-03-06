# Hibernate Search database back end processor

This is an alternative back end processor to those already provided with Hibernate Search (`LUCENE`, `JMS` and `JGROUPS`). It stores its scheduled index updates (LuceneWork's) in a database for later processing by a scheduled job. It is designed to work single node environments as well as clustered environments. 

Primary advantages:
- Once persisted to the database, scheduled index updates are not lost in case of node crashes  
- No need for specifying master/slave roles in a cluster (this is contrary to `JMS` processor)

To get these advantages, however, you yourself have to define a job that only run in one of the nodes in cluster environment. This can, for example, be done with [Quartz](http://quartz-scheduler.org/) or a [Wildfly HA singleton](https://github.com/wildfly/quickstart/tree/master/cluster-ha-singleton).

Note that this processor runs in a post-successful transaction commit (indirectly orchestrated by Hibernate via a JTA `Synchronization` listener). As such there is a narrow window of vulnerability, in which a crash can result in lost index updates. However, do note that the same applies for all of the Hibernate Search built-in processors as well. 

_This processor has only been tested with WildFly 8.2 and Hibernate Search 4.5.1. However, configuration options will most likely make it work without issues in other application server configurations as well._

## How do I install it?

That's easy 

* Download the jar file from [releases](https://github.com/umbrew/org.umbrew.hibernate.database.worker.backend/releases) or checkout the code and build it yourself.
* Place the jar file in your EAR- or WAR archive.
* Update your persistence.xml with following configuration

> &#60;property name="hibernate.search.default.worker.backend" value="org.umbrew.hibernate.search.database.worker.backend.DatabaseBackendQueueProcessor"/&#62;

> &#60;property name="hibernate.search.default.worker.backend.hibernate.connection.datasource" value="REPLACE_ME"/&#62;

### Create a Job for processing Lucene workers
You will have to do a little work for yourself: create a job or HA singleton that is only running on a single node at a each time. 

The job has to extend this class:

>AbstractDatabaseHibernateSearchController

and let it call the method

>processWorkQueue()

A common paradigm to initiate the controller could look like this:

```java
@Startup
@Singleton
@LocalBean
@ConcurrencyManagement(BEAN)
public class DatabaseHibernateSearchController extends AbstractDatabaseHibernateSearchController {

    @PersistenceContext(name = "myPersistenceUnit") //point to the persistence context that is configured to use Hibernate Search
    private EntityManager entityManager;

    @Override
    protected Session getSession() {
    	// Unwrap the Hibernate session object
        return (Session) entityManager.getDelegate(); 
    }

    @PostConstruct
    public void start() {
    
        // Schedule your cluster-aware Quartz job here
        // (ensure that the job is only run by one cluster node at any given time)
        
        // The job that you schedule should call the processWorkQueue() method in the super class  
        // (this singleton is extending AbstractDatabaseHibernateSearchController)
        // (alternatively you could let the job itself extend the AbstractDatabaseHibernateSearchController)
        
    }

}
``` 

The above example illustrates how to use an EJB singleton component to schedule a quartzjob - which then again, periodically invokes the `processWorkQueue()` method.

Alternatively, if your EJB container already supports clusterwide Timer beans, then you could also just use that. Or something completely different, the choice is all yours.

Just remember one thing: your application must have some "job-like" functionality that periodically calls the  `processWorkQueue()`  method.
Failure to have this will result in two problems. Firstly, nothing will ever be indexed. Secondly, at some point in time your database will hit excessive disk usage problems - caused by an ever-growing amount of rows in table `lucene_work`.

### Workaround for orphan locks with Infinispan

There is a possibility when running the AbstractDatabaseHibernateSearchController clustered you could end up with orphan locks if you are using Infinispan as directory with replicated/distributed locks and the node crash. This will prevent the IndexWriter to acquire a lock on one of the remaining nodes.

The trick here is to change the LuceneIndexesLocking to a local cache and prevent distributed locks. This will of course only work if processWorkQueue run exclusive on one node. 

```xml
<local-cache name="LuceneIndexesLocking" start="EAGER">
   <eviction strategy="NONE" max-entries="-1"/>
   <expiration max-idle="-1"/>
   <indexing index="NONE"/>
</local-cache>
```


## Configuration
The following configuration is supported in the persistence.xml

| Key  | Value   |
|---|---|
|hibernate.search.default.worker.backend   |org.umbrew.hibernate.search.database.worker.backend.DatabaseBackendQueueProcessor|
|hibernate.search.default.worker.backend.hibernate.transaction.jta.platform   | Set the supported JTA platform. Default "org.hibernate.engine.transaction.jta.platform.internal.JBossAppServerJtaPlatform"   |
|hibernate.search.default.worker.backend.hibernate.connection.datasource   | Set the datasource the worker should connect to |
|hibernate.search.default.worker.backend.hibernate.hbm2ddl.auto   | Set the schema creation mode. Defaults to "update" (Follow hibernate semantic) |
|hibernate.search.default.worker.backend.hibernate.show_sql   | Show the executed SQL. Defaults to "false"  |
|hibernate.search.default.worker.backend.hibernate.format_sql   | Pretty print the executed SQL. Default to "false"  |

Other Hibernate properties are supported, prefixed with `hibernate.search.default.worker.backend.`.

## How do I build it?

Easy again, just type the following command

> mvn clean install -P wildfly
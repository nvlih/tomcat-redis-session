package com.jingtum.tomcat.redissessions;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Loader;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Session;
import org.apache.catalina.Valve;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.util.Pool;
public class RedisSessionManager extends ManagerBase
        implements Lifecycle
{
    protected byte[] NULL_SESSION = "null".getBytes();

    private final Log log = LogFactory.getLog(RedisSessionManager.class);

    protected String host = "localhost";
    protected int port = 6379;
    protected int database = 0;
    protected String password = "";
    protected int timeout = 2000;
    protected String sentinelMaster = null;
    Set<String> sentinelSet = null;
    protected Pool<Jedis> connectionPool;
    protected JedisPoolConfig connectionPoolConfig = new JedisPoolConfig();
    protected RedisSessionHandlerValve handlerValve;
    protected ThreadLocal<RedisSession> currentSession = new ThreadLocal();
    protected ThreadLocal<SessionSerializationMetadata> currentSessionSerializationMetadata = new ThreadLocal();
    protected ThreadLocal<String> currentSessionId = new ThreadLocal();
    protected ThreadLocal<Boolean> currentSessionIsPersisted = new ThreadLocal();
    protected Serializer serializer;
    protected static String name = "RedisSessionManager";

    protected String serializationStrategyClass = "com.jingtum.tomcat.redissessions.JavaSerializer";

    protected EnumSet<SessionPersistPolicy> sessionPersistPoliciesSet = EnumSet.of(SessionPersistPolicy.DEFAULT);

    protected LifecycleSupport lifecycle = new LifecycleSupport(this);

    public String getHost() {
        return this.host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return this.port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getDatabase() {
        return this.database;
    }

    public void setDatabase(int database) {
        this.database = database;
    }

    public int getTimeout() {
        return this.timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public String getPassword() {
        return this.password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setSerializationStrategyClass(String strategy) {
        this.serializationStrategyClass = strategy;
    }

    public String getSessionPersistPolicies() {
        StringBuilder policies = new StringBuilder();
        for (Iterator iter = this.sessionPersistPoliciesSet.iterator(); iter.hasNext(); ) {
            SessionPersistPolicy policy = (SessionPersistPolicy)iter.next();
            policies.append(policy.name());
            if (iter.hasNext()) {
                policies.append(",");
            }
        }
        return policies.toString();
    }

    public void setSessionPersistPolicies(String sessionPersistPolicies) {
        String[] policyArray = sessionPersistPolicies.split(",");
        EnumSet policySet = EnumSet.of(SessionPersistPolicy.DEFAULT);
        for (String policyName : policyArray) {
            SessionPersistPolicy policy = SessionPersistPolicy.fromName(policyName);
            policySet.add(policy);
        }
        this.sessionPersistPoliciesSet = policySet;
    }

    public boolean getSaveOnChange() {
        return this.sessionPersistPoliciesSet.contains(SessionPersistPolicy.SAVE_ON_CHANGE);
    }

    public boolean getAlwaysSaveAfterRequest() {
        return this.sessionPersistPoliciesSet.contains(SessionPersistPolicy.ALWAYS_SAVE_AFTER_REQUEST);
    }

    public String getSentinels() {
        StringBuilder sentinels = new StringBuilder();
        for (Iterator iter = this.sentinelSet.iterator(); iter.hasNext(); ) {
            sentinels.append((String)iter.next());
            if (iter.hasNext()) {
                sentinels.append(",");
            }
        }
        return sentinels.toString();
    }

    public void setSentinels(String sentinels) {
        if (null == sentinels) {
            sentinels = "";
        }

        String[] sentinelArray = sentinels.split(",");
        this.sentinelSet = new HashSet(Arrays.asList(sentinelArray));
    }

    public Set<String> getSentinelSet() {
        return this.sentinelSet;
    }

    public String getSentinelMaster() {
        return this.sentinelMaster;
    }

    public void setSentinelMaster(String master) {
        this.sentinelMaster = master;
    }

    public int getRejectedSessions()
    {
        return 0;
    }

    public void setRejectedSessions(int i)
    {
    }

    protected Jedis acquireConnection() {
        Jedis jedis = (Jedis)this.connectionPool.getResource();

        if (getDatabase() != 0) {
            jedis.select(getDatabase());
        }

        return jedis;
    }

    protected void returnConnection(Jedis jedis, Boolean error) {
        if (error.booleanValue())
            this.connectionPool.returnBrokenResource(jedis);
        else
            this.connectionPool.returnResource(jedis);
    }

    protected void returnConnection(Jedis jedis)
    {
        returnConnection(jedis, Boolean.valueOf(false));
    }

    public void load()
            throws ClassNotFoundException, IOException
    {
    }

    public void unload()
            throws IOException
    {
    }

    public void addLifecycleListener(LifecycleListener listener)
    {
        this.lifecycle.addLifecycleListener(listener);
    }

    public LifecycleListener[] findLifecycleListeners()
    {
        return this.lifecycle.findLifecycleListeners();
    }

    public void removeLifecycleListener(LifecycleListener listener)
    {
        this.lifecycle.removeLifecycleListener(listener);
    }

    protected synchronized void startInternal()
            throws LifecycleException
    {
        super.startInternal();

        setState(LifecycleState.STARTING);

        Boolean attachedToValve = Boolean.valueOf(false);
        for (Valve valve : getContainer().getPipeline().getValves()) {
            if ((valve instanceof RedisSessionHandlerValve)) {
                this.handlerValve = ((RedisSessionHandlerValve)valve);
                this.handlerValve.setRedisSessionManager(this);
                this.log.info("Attached to RedisSessionHandlerValve");
                attachedToValve = Boolean.valueOf(true);
                break;
            }
        }

        if (!attachedToValve.booleanValue()) {
            String error = "Unable to attach to session handling valve; sessions cannot be saved after the request without the valve starting properly.";
            this.log.fatal(error);
            throw new LifecycleException(error);
        }
        try
        {
            initializeSerializer();
        } catch (Exception e) {
            this.log.fatal("Unable to load serializer", e);
            throw new LifecycleException(e);
        }

        this.log.info(new StringBuilder().append("Will expire sessions after ").append(getMaxInactiveInterval()).append(" seconds").toString());

        initializeDatabaseConnection();

        setDistributable(true);
    }

    protected synchronized void stopInternal()
            throws LifecycleException
    {
        if (this.log.isDebugEnabled()) {
            this.log.debug("Stopping");
        }

        setState(LifecycleState.STOPPING);
        try
        {
            this.connectionPool.destroy();
        }
        catch (Exception localException)
        {
        }

        super.stopInternal();
    }

    public Session createSession(String requestedSessionId)
    {
        RedisSession session = null;
        String sessionId = null;
        String jvmRoute = getJvmRoute();

        Boolean error = Boolean.valueOf(true);
        Jedis jedis = null;
        try {
            jedis = acquireConnection();

            if (null != requestedSessionId) {
                sessionId = sessionIdWithJvmRoute(requestedSessionId, jvmRoute);
                if (jedis.setnx(sessionId.getBytes(), this.NULL_SESSION).longValue() == 0L)
                    sessionId = null;
            }
            else {
                do
                    sessionId = sessionIdWithJvmRoute(generateSessionId(), jvmRoute);
                while (jedis.setnx(sessionId.getBytes(), this.NULL_SESSION).longValue() == 0L);
            }

            error = Boolean.valueOf(false);

            if (null != sessionId) {
                session = (RedisSession)createEmptySession();
                session.setNew(true);
                session.setValid(true);
                session.setCreationTime(System.currentTimeMillis());
                session.setMaxInactiveInterval(getMaxInactiveInterval());
                session.setId(sessionId);
                session.tellNew();
            }

            this.currentSession.set(session);
            this.currentSessionId.set(sessionId);
            this.currentSessionIsPersisted.set(Boolean.valueOf(false));
            this.currentSessionSerializationMetadata.set(new SessionSerializationMetadata());

            if (null != session)
                try {
                    error = Boolean.valueOf(saveInternal(jedis, session, true));
                } catch (IOException ex) {
                    this.log.error(new StringBuilder().append("Error saving newly created session: ").append(ex.getMessage()).toString());
                    this.currentSession.set(null);
                    this.currentSessionId.set(null);
                    session = null;
                }
        }
        finally {
            if (jedis != null) {
                returnConnection(jedis, error);
            }
        }

        return session;
    }

    private String sessionIdWithJvmRoute(String sessionId, String jvmRoute) {
        if (jvmRoute != null) {
            String jvmRoutePrefix = new StringBuilder().append('.').append(jvmRoute).toString();
            return sessionId.endsWith(jvmRoutePrefix) ? sessionId : new StringBuilder().append(sessionId).append(jvmRoutePrefix).toString();
        }
        return sessionId;
    }

    public Session createEmptySession()
    {
        return new RedisSession(this);
    }

    public void add(Session session)
    {
        try {
            save(session);
        } catch (IOException ex) {
            this.log.warn(new StringBuilder().append("Unable to add to session manager store: ").append(ex.getMessage()).toString());
            throw new RuntimeException("Unable to add to session manager store.", ex);
        }
    }

    public Session findSession(String id) throws IOException
    {
        RedisSession session = null;

        if (null == id) {
            this.currentSessionIsPersisted.set(Boolean.valueOf(false));
            this.currentSession.set(null);
            this.currentSessionSerializationMetadata.set(null);
            this.currentSessionId.set(null);
        } else if (id.equals(this.currentSessionId.get())) {
            session = (RedisSession)this.currentSession.get();
        } else {
            byte[] data = loadSessionDataFromRedis(id);
            if (data != null) {
                DeserializedSessionContainer container = sessionFromSerializedData(id, data);
                session = container.session;
                this.currentSession.set(session);
                this.currentSessionSerializationMetadata.set(container.metadata);
                this.currentSessionIsPersisted.set(Boolean.valueOf(true));
                this.currentSessionId.set(id);
            } else {
                this.currentSessionIsPersisted.set(Boolean.valueOf(false));
                this.currentSession.set(null);
                this.currentSessionSerializationMetadata.set(null);
                this.currentSessionId.set(null);
            }
        }

        return session;
    }

    public void clear() {
        Jedis jedis = null;
        Boolean error = Boolean.valueOf(true);
        try {
            jedis = acquireConnection();
            jedis.flushDB();
            error = Boolean.valueOf(false);

            if (jedis != null)
                returnConnection(jedis, error);
        }
        finally
        {
            if (jedis != null)
                returnConnection(jedis, error);
        }
    }

    public int getSize() throws IOException
    {
        Jedis jedis = null;
        Boolean error = Boolean.valueOf(true);
        try {
            jedis = acquireConnection();
            int size = jedis.dbSize().intValue();
            error = Boolean.valueOf(false);
            return size;
        } finally {
            if (jedis != null)
                returnConnection(jedis, error);
        }
    }

    public String[] keys() throws IOException
    {
        Jedis jedis = null;
        Boolean error = Boolean.valueOf(true);
        try {
            jedis = acquireConnection();
            Set keySet = jedis.keys("*");
            error = Boolean.valueOf(false);
            return (String[])keySet.toArray(new String[keySet.size()]);
        } finally {
            if (jedis != null)
                returnConnection(jedis, error);
        }
    }

    public byte[] loadSessionDataFromRedis(String id) throws IOException
    {
        Jedis jedis = null;
        Boolean error = Boolean.valueOf(true);
        try
        {
            this.log.trace(new StringBuilder().append("Attempting to load session ").append(id).append(" from Redis").toString());

            jedis = acquireConnection();
            byte[] data = jedis.get(id.getBytes());
            error = Boolean.valueOf(false);

            if (data == null) {
                this.log.trace(new StringBuilder().append("Session ").append(id).append(" not found in Redis").toString());
            }

            return data;
        } finally {
            if (jedis != null)
                returnConnection(jedis, error);
        }
    }

    public DeserializedSessionContainer sessionFromSerializedData(String id, byte[] data) throws IOException
    {
        this.log.trace(new StringBuilder().append("Deserializing session ").append(id).append(" from Redis").toString());

        if (Arrays.equals(this.NULL_SESSION, data)) {
            this.log.error(new StringBuilder().append("Encountered serialized session ").append(id).append(" with data equal to NULL_SESSION. This is a bug.").toString());
            throw new IOException("Serialized session data was equal to NULL_SESSION");
        }

        RedisSession session = null;
        SessionSerializationMetadata metadata = new SessionSerializationMetadata();
        try
        {
            session = (RedisSession)createEmptySession();

            this.serializer.deserializeInto(data, session, metadata);

            session.setId(id);
            session.setNew(false);
            session.setMaxInactiveInterval(getMaxInactiveInterval());
            session.access();
            session.setValid(true);
            session.resetDirtyTracking();

            if (this.log.isTraceEnabled()) {
                this.log.trace(new StringBuilder().append("Session Contents [").append(id).append("]:").toString());
                Enumeration en = session.getAttributeNames();
                while (en.hasMoreElements())
                    this.log.trace(new StringBuilder().append("  ").append(en.nextElement()).toString());
            }
        }
        catch (ClassNotFoundException ex) {
            this.log.fatal("Unable to deserialize into session", ex);
            throw new IOException("Unable to deserialize into session", ex);
        }

        return new DeserializedSessionContainer(session, metadata);
    }

    public void save(Session session) throws IOException {
        save(session, false);
    }

    public void save(Session session, boolean forceSave) throws IOException {
        Jedis jedis = null;
        Boolean error = Boolean.valueOf(true);
        try
        {
            jedis = acquireConnection();
            error = Boolean.valueOf(saveInternal(jedis, session, forceSave));
        } catch (IOException e) {
            throw e;
        } finally {
            if (jedis != null)
                returnConnection(jedis, error);
        }
    }

    protected boolean saveInternal(Jedis jedis, Session session, boolean forceSave) throws IOException
    {
        Boolean error = Boolean.valueOf(true);
        try
        {
            this.log.trace(new StringBuilder().append("Saving session ").append(session).append(" into Redis").toString());

            RedisSession redisSession = (RedisSession)session;

            if (this.log.isTraceEnabled()) {
                this.log.trace(new StringBuilder().append("Session Contents [").append(redisSession.getId()).append("]:").toString());
                Enumeration en = redisSession.getAttributeNames();
                while (en.hasMoreElements()) {
                    this.log.trace(new StringBuilder().append("  ").append(en.nextElement()).toString());
                }
            }

            byte[] binaryId = redisSession.getId().getBytes();

            SessionSerializationMetadata sessionSerializationMetadata = (SessionSerializationMetadata)this.currentSessionSerializationMetadata.get();
            byte[] originalSessionAttributesHash = sessionSerializationMetadata.getSessionAttributesHash();
            byte[] sessionAttributesHash = null;
            Boolean isCurrentSessionPersisted;
            if ((forceSave) ||
                    (redisSession
                            .isDirty().booleanValue()) ||
                    (null ==
                            (isCurrentSessionPersisted = (Boolean)this.currentSessionIsPersisted
                                    .get())) ||
                    (!isCurrentSessionPersisted
                            .booleanValue()) ||
                    (!Arrays.equals(originalSessionAttributesHash,
                            sessionAttributesHash = this.serializer
                                    .attributesHashFrom(redisSession))))
            {
                this.log.trace("Save was determined to be necessary");

                if (null == sessionAttributesHash) {
                    sessionAttributesHash = this.serializer.attributesHashFrom(redisSession);
                }

                SessionSerializationMetadata updatedSerializationMetadata = new SessionSerializationMetadata();
                updatedSerializationMetadata.setSessionAttributesHash(sessionAttributesHash);

                jedis.set(binaryId, this.serializer.serializeFrom(redisSession, updatedSerializationMetadata));

                redisSession.resetDirtyTracking();
                this.currentSessionSerializationMetadata.set(updatedSerializationMetadata);
                this.currentSessionIsPersisted.set(Boolean.valueOf(true));
            }
            else
            {
                this.log.trace("Save was determined to be unnecessary");
            }

            this.log.trace(new StringBuilder().append("Setting expire timeout on session [").append(redisSession.getId()).append("] to ").append(getMaxInactiveInterval()).toString());
            jedis.expire(binaryId, getMaxInactiveInterval());

            error = Boolean.valueOf(false);
            return error.booleanValue();

        } catch (IOException e)
        {
            this.log.error(e.getMessage());
            throw e;
        }finally {
            return error;
        }


    }

    public void remove(Session session)
    {
        remove(session, false);
    }

    public void remove(Session session, boolean update)
    {
        Jedis jedis = null;
        Boolean error = Boolean.valueOf(true);

        this.log.trace(new StringBuilder().append("Removing session ID : ").append(session.getId()).toString());
        try
        {
            jedis = acquireConnection();
            jedis.del(session.getId());
            error = Boolean.valueOf(false);
        } finally {
            if (jedis != null)
                returnConnection(jedis, error);
        }
    }

    public void afterRequest()
    {
        RedisSession redisSession = (RedisSession)this.currentSession.get();
        if (redisSession != null)
            try {
                if (redisSession.isValid()) {
                    this.log.trace(new StringBuilder().append("Request with session completed, saving session ").append(redisSession.getId()).toString());
                    save(redisSession, getAlwaysSaveAfterRequest());
                } else {
                    this.log.trace(new StringBuilder().append("HTTP Session has been invalidated, removing :").append(redisSession.getId()).toString());
                    remove(redisSession);
                }
            } catch (Exception e) {
                this.log.error("Error storing/removing session", e);
            } finally {
                this.currentSession.remove();
                this.currentSessionId.remove();
                this.currentSessionIsPersisted.remove();
                this.log.trace(new StringBuilder().append("Session removed from ThreadLocal :").append(redisSession.getIdInternal()).toString());
            }
    }

    public void processExpires()
    {
    }

    private void initializeDatabaseConnection()
            throws LifecycleException
    {
        try
        {
            if (getSentinelMaster() != null) {
                Set sentinelSet = getSentinelSet();
                if ((sentinelSet != null) && (sentinelSet.size() > 0))
                    this.connectionPool = new JedisSentinelPool(getSentinelMaster(), sentinelSet, this.connectionPoolConfig, getTimeout(), getPassword());
                else
                    throw new LifecycleException("Error configuring Redis Sentinel connection pool: expected both `sentinelMaster` and `sentiels` to be configured");
            }
            else {
                this.connectionPool = new JedisPool(this.connectionPoolConfig, getHost(), getPort(), getTimeout(), getPassword());
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new LifecycleException("Error connecting to Redis", e);
        }
    }

    private void initializeSerializer() throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        this.log.info(new StringBuilder().append("Attempting to use serializer :").append(this.serializationStrategyClass).toString());
        this.serializer = ((Serializer)Class.forName(this.serializationStrategyClass).newInstance());

        Loader loader = null;
        Context context = getContext();
        if (context != null) {
            loader = context.getLoader();
        }

        ClassLoader classLoader = null;

        if (loader != null) {
            classLoader = loader.getClassLoader();
        }
        this.serializer.setClassLoader(classLoader);
    }

    public int getConnectionPoolMaxTotal()
    {
        return this.connectionPoolConfig.getMaxTotal();
    }

    public void setConnectionPoolMaxTotal(int connectionPoolMaxTotal) {
        this.connectionPoolConfig.setMaxTotal(connectionPoolMaxTotal);
    }

    public int getConnectionPoolMaxIdle() {
        return this.connectionPoolConfig.getMaxIdle();
    }

    public void setConnectionPoolMaxIdle(int connectionPoolMaxIdle) {
        this.connectionPoolConfig.setMaxIdle(connectionPoolMaxIdle);
    }

    public int getConnectionPoolMinIdle() {
        return this.connectionPoolConfig.getMinIdle();
    }

    public void setConnectionPoolMinIdle(int connectionPoolMinIdle) {
        this.connectionPoolConfig.setMinIdle(connectionPoolMinIdle);
    }

    public boolean getLifo()
    {
        return this.connectionPoolConfig.getLifo();
    }
    public void setLifo(boolean lifo) {
        this.connectionPoolConfig.setLifo(lifo);
    }
    public long getMaxWaitMillis() {
        return this.connectionPoolConfig.getMaxWaitMillis();
    }

    public void setMaxWaitMillis(long maxWaitMillis) {
        this.connectionPoolConfig.setMaxWaitMillis(maxWaitMillis);
    }

    public long getMinEvictableIdleTimeMillis() {
        return this.connectionPoolConfig.getMinEvictableIdleTimeMillis();
    }

    public void setMinEvictableIdleTimeMillis(long minEvictableIdleTimeMillis) {
        this.connectionPoolConfig.setMinEvictableIdleTimeMillis(minEvictableIdleTimeMillis);
    }

    public long getSoftMinEvictableIdleTimeMillis() {
        return this.connectionPoolConfig.getSoftMinEvictableIdleTimeMillis();
    }

    public void setSoftMinEvictableIdleTimeMillis(long softMinEvictableIdleTimeMillis) {
        this.connectionPoolConfig.setSoftMinEvictableIdleTimeMillis(softMinEvictableIdleTimeMillis);
    }

    public int getNumTestsPerEvictionRun() {
        return this.connectionPoolConfig.getNumTestsPerEvictionRun();
    }

    public void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
        this.connectionPoolConfig.setNumTestsPerEvictionRun(numTestsPerEvictionRun);
    }

    public boolean getTestOnBorrow()
    {
        return this.connectionPoolConfig.getTestOnBorrow();
    }

    public void setTestOnBorrow(boolean testOnBorrow) {
        this.connectionPoolConfig.setTestOnBorrow(testOnBorrow);
    }

    public boolean getTestOnReturn() {
        return this.connectionPoolConfig.getTestOnReturn();
    }

    public void setTestOnReturn(boolean testOnReturn) {
        this.connectionPoolConfig.setTestOnReturn(testOnReturn);
    }

    public boolean getTestWhileIdle() {
        return this.connectionPoolConfig.getTestWhileIdle();
    }

    public void setTestWhileIdle(boolean testWhileIdle) {
        this.connectionPoolConfig.setTestWhileIdle(testWhileIdle);
    }

    public long getTimeBetweenEvictionRunsMillis() {
        return this.connectionPoolConfig.getTimeBetweenEvictionRunsMillis();
    }

    public void setTimeBetweenEvictionRunsMillis(long timeBetweenEvictionRunsMillis) {
        this.connectionPoolConfig.setTimeBetweenEvictionRunsMillis(timeBetweenEvictionRunsMillis);
    }

    public String getEvictionPolicyClassName() {
        return this.connectionPoolConfig.getEvictionPolicyClassName();
    }

    public void setEvictionPolicyClassName(String evictionPolicyClassName) {
        this.connectionPoolConfig.setEvictionPolicyClassName(evictionPolicyClassName);
    }

    public boolean getBlockWhenExhausted() {
        return this.connectionPoolConfig.getBlockWhenExhausted();
    }

    public void setBlockWhenExhausted(boolean blockWhenExhausted) {
        this.connectionPoolConfig.setBlockWhenExhausted(blockWhenExhausted);
    }

    public boolean getJmxEnabled() {
        return this.connectionPoolConfig.getJmxEnabled();
    }

    public void setJmxEnabled(boolean jmxEnabled) {
        this.connectionPoolConfig.setJmxEnabled(jmxEnabled);
    }

    public String getJmxNamePrefix() {
        return this.connectionPoolConfig.getJmxNamePrefix();
    }

    public void setJmxNamePrefix(String jmxNamePrefix) {
        this.connectionPoolConfig.setJmxNamePrefix(jmxNamePrefix);
    }

    static enum SessionPersistPolicy
    {
        DEFAULT,
        SAVE_ON_CHANGE,
        ALWAYS_SAVE_AFTER_REQUEST;

        static SessionPersistPolicy fromName(String name) {
            for (SessionPersistPolicy policy : values()) {
                if (policy.name().equalsIgnoreCase(name)) {
                    return policy;
                }
            }
            throw new IllegalArgumentException("Invalid session persist policy [" + name + "]. Must be one of " + Arrays.asList(values()) + ".");
        }
    }
}

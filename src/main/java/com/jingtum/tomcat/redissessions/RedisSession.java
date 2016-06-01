package com.jingtum.tomcat.redissessions;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.Principal;
import java.util.HashMap;
import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

public class RedisSession  extends StandardSession
{
    private final Log log = LogFactory.getLog(RedisSession.class);

    protected static Boolean manualDirtyTrackingSupportEnabled = Boolean.valueOf(false);

    protected static String manualDirtyTrackingAttributeKey = "__changed__";
    protected HashMap<String, Object> changedAttributes;
    protected Boolean dirty;

    public static void setManualDirtyTrackingSupportEnabled(Boolean enabled)
    {
        manualDirtyTrackingSupportEnabled = enabled;
    }

    public static void setManualDirtyTrackingAttributeKey(String key)
    {
        manualDirtyTrackingAttributeKey = key;
    }

    public RedisSession(Manager manager)
    {
        super(manager);
        resetDirtyTracking();
    }

    public Boolean isDirty() {
        return Boolean.valueOf((this.dirty.booleanValue()) || (!this.changedAttributes.isEmpty()));
    }

    public HashMap<String, Object> getChangedAttributes() {
        return this.changedAttributes;
    }

    public void resetDirtyTracking() {
        this.changedAttributes = new HashMap();
        this.dirty = Boolean.valueOf(false);
    }

    public void setAttribute(String key, Object value)
    {
        if ((manualDirtyTrackingSupportEnabled.booleanValue()) && (manualDirtyTrackingAttributeKey.equals(key))) {
            this.dirty = Boolean.valueOf(true);
            return;
        }

        Object oldValue = getAttribute(key);
        super.setAttribute(key, value);

        if (((value != null) || (oldValue != null)) && (((value == null) && (oldValue != null)) || ((oldValue == null) && (value != null)) ||
                (!value
                        .getClass().isInstance(oldValue)) ||
                (!value
                        .equals(oldValue))))
        {
            if (((this.manager instanceof RedisSessionManager)) &&
                    (((RedisSessionManager)this.manager)
                            .getSaveOnChange()))
                try {
                    ((RedisSessionManager)this.manager).save(this, true);
                } catch (IOException ex) {
                    this.log.error("Error saving session on setAttribute (triggered by saveOnChange=true): " + ex.getMessage());
                }
            else
                this.changedAttributes.put(key, value);
        }
    }

    public void removeAttribute(String name)
    {
        super.removeAttribute(name);
        if (((this.manager instanceof RedisSessionManager)) &&
                (((RedisSessionManager)this.manager)
                        .getSaveOnChange()))
            try {
                ((RedisSessionManager)this.manager).save(this, true);
            } catch (IOException ex) {
                this.log.error("Error saving session on setAttribute (triggered by saveOnChange=true): " + ex.getMessage());
            }
        else
            this.dirty = Boolean.valueOf(true);
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public void setPrincipal(Principal principal)
    {
        this.dirty = Boolean.valueOf(true);
        super.setPrincipal(principal);
    }

    public void writeObjectData(ObjectOutputStream out) throws IOException
    {
        super.writeObjectData(out);
        out.writeLong(getCreationTime());
    }

    public void readObjectData(ObjectInputStream in) throws IOException, ClassNotFoundException
    {
        super.readObjectData(in);
        setCreationTime(in.readLong());
    }
}

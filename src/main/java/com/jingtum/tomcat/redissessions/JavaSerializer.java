package com.jingtum.tomcat.redissessions;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashMap;
import org.apache.catalina.util.CustomObjectInputStream;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

public class JavaSerializer implements Serializer {
    private ClassLoader loader;
    private final Log log = LogFactory.getLog(JavaSerializer.class);

    public void setClassLoader(ClassLoader loader) {
        this.loader = loader;
    }

    public byte[] attributesHashFrom(RedisSession session) throws IOException {
        HashMap<String, Object> attributes = new HashMap<String, Object>();
        for (Enumeration<String> enumerator = session.getAttributeNames(); enumerator.hasMoreElements(); ) {
            String key = enumerator.nextElement();
            attributes.put(key, session.getAttribute(key));
        }
        Throwable localThrowable = null;
        byte[] serialized = null;
        MessageDigest digester = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(bos));
        try {
            oos.writeUnshared(attributes);
            oos.flush();
            serialized = bos.toByteArray();
        } catch (IOException iOException) {
            throw iOException;
        } finally {
            if (bos != null) {
                bos.close();
            }
            if (oos != null) {
                oos.close();
            }
        }
        try {
            digester = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            log.error("Unable to get MessageDigest instance for MD5");
        }
        return digester.digest(serialized);
    }

    public byte[] serializeFrom(RedisSession session, SessionSerializationMetadata metadata) throws IOException {
        byte[] serialized = null;

        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(bos));

        try {
            oos.writeObject(metadata);
            session.writeObjectData(oos);
            oos.flush();
            serialized = bos.toByteArray();
        } catch (IOException iOException) {
            throw iOException;
        } finally {
            if (bos != null) {
                bos.close();
            }
            if (oos != null) {
                oos.close();
            }
        }
        return serialized;
    }

    public void deserializeInto(byte[] data, RedisSession session, SessionSerializationMetadata metadata)
            throws IOException, ClassNotFoundException
    {
        BufferedInputStream bis = new BufferedInputStream(new ByteArrayInputStream(data));


            ObjectInputStream ois = new CustomObjectInputStream(bis, this.loader);

            try
            {
                SessionSerializationMetadata serializedMetadata = (SessionSerializationMetadata)ois.readObject();
                metadata.copyFieldsFrom(serializedMetadata);
                session.readObjectData(ois);
            }
            catch (IOException iOException)
            {
                throw iOException;
            }
            finally {
                if (bis != null) {
                    bis.close();
                }
                if (ois != null) {
                    ois.close();
                }
            }
    }
}

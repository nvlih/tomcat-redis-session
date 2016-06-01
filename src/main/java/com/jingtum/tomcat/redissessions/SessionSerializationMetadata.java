package com.jingtum.tomcat.redissessions;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;

public class SessionSerializationMetadata  implements Serializable
{
    private byte[] sessionAttributesHash;

    public SessionSerializationMetadata()
    {
        this.sessionAttributesHash = new byte[0];
    }

    public byte[] getSessionAttributesHash() {
        return this.sessionAttributesHash;
    }

    public void setSessionAttributesHash(byte[] sessionAttributesHash) {
        this.sessionAttributesHash = sessionAttributesHash;
    }

    public void copyFieldsFrom(SessionSerializationMetadata metadata) {
        setSessionAttributesHash(metadata.getSessionAttributesHash());
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.writeInt(this.sessionAttributesHash.length);
        out.write(this.sessionAttributesHash);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        int hashLength = in.readInt();
        byte[] sessionAttributesHash = new byte[hashLength];
        in.read(sessionAttributesHash, 0, hashLength);
        this.sessionAttributesHash = sessionAttributesHash;
    }

    private void readObjectNoData() throws ObjectStreamException {
        this.sessionAttributesHash = new byte[0];
    }
}

package com.jingtum.tomcat.redissessions;

public class DeserializedSessionContainer {
    public final RedisSession session;
    public final SessionSerializationMetadata metadata;

    public DeserializedSessionContainer(RedisSession session, SessionSerializationMetadata metadata)
    {
        this.session = session;
        this.metadata = metadata;
    }
}

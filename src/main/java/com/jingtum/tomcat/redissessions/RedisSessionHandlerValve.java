package com.jingtum.tomcat.redissessions;

import java.io.IOException;
import javax.servlet.ServletException;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

public class RedisSessionHandlerValve extends ValveBase
{
    private final Log log = LogFactory.getLog(RedisSessionManager.class);
    private RedisSessionManager manager;

    public void setRedisSessionManager(RedisSessionManager manager)
    {
        this.manager = manager;
    }

    public void invoke(Request request, Response response) throws IOException, ServletException
    {
        try {
            getNext().invoke(request, response);

            this.manager.afterRequest(); } finally { this.manager.afterRequest(); }

    }
}
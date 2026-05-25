package com.jujin.freeway.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Thin websocket session view exposed to application code.
 */
public interface WebSocketSession {
    String method();

    String path();

    String pathVar(String name);

    Map<String, String> pathVars();

    String queryParam(String name);

    List<String> queryParams(String name);

    Map<String, List<String>> queryParams();

    String header(String name);

    List<String> headers(String name);

    RequestContext requestContext();

    boolean isOpen();

    void sendText(String text) throws IOException;

    void sendBinary(byte[] data) throws IOException;

    void ping(byte[] data) throws IOException;

    void close(int code, String reason) throws IOException;
}

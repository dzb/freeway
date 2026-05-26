package com.jujin.freeway.http;

import java.io.IOException;

public interface HttpEngine {
    HttpServerHandle start(HttpServerConfig config, HttpRequestHandler handler) throws IOException;
}

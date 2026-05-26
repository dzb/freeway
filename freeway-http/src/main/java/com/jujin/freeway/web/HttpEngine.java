package com.jujin.freeway.web;

import java.io.IOException;

public interface HttpEngine {
    WebServerHandle start(WebServerConfig config, WebRequestHandler handler) throws IOException;
}

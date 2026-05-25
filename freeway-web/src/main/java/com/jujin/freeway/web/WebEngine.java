package com.jujin.freeway2.web;

import java.io.IOException;

public interface WebEngine {
    WebServerHandle start(WebServerConfig config, WebRequestHandler handler) throws IOException;
}

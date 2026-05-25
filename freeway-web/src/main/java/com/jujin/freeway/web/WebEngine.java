package com.jujin.freeway.web;

import java.io.IOException;

public interface WebEngine {
    WebServerHandle start(WebServerConfig config, WebRequestHandler handler) throws IOException;
}

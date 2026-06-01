package com.jujin.freeway.http;

import com.jujin.freeway.http.engine.AbstractWebEngineContractTest;

class JettyWebEngineContractTest extends AbstractWebEngineContractTest {
    @Override
    protected String engineId() {
        return "jetty";
    }

    @Override
    protected Class<? extends HttpEngine> engineType() {
        return JettyWebEngine.class;
    }
}

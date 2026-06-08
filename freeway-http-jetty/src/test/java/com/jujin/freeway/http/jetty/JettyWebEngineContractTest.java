package com.jujin.freeway.http.jetty;

import com.jujin.freeway.http.HttpEngine;
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

package com.jujin.freeway.http;

import com.jujin.freeway.http.engine.AbstractWebEngineContractTest;

class UndertowWebEngineContractTest extends AbstractWebEngineContractTest {
    @Override
    protected String engineId() {
        return "undertow";
    }

    @Override
    protected Class<? extends HttpEngine> engineType() {
        return UndertowWebEngine.class;
    }
}

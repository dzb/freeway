package com.jujin.freeway.http.undertow;

import com.jujin.freeway.http.HttpEngine;
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

package com.jujin.freeway.http.robaho;

import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.http.engine.AbstractWebEngineContractTest;

class RobahoWebEngineContractTest extends AbstractWebEngineContractTest {
    @Override
    protected String engineId() {
        return "robaho";
    }

    @Override
    protected Class<? extends HttpEngine> engineType() {
        return RobahoWebEngine.class;
    }
}

package com.jujin.freeway.db.hikari;

import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;

public final class HikariPoolModule implements Module {

    @Override
    public void bind(Binder binder) {
        binder.bind(HikariPool.class)
            .to(container -> {
                PoolConfig config = container.get(PoolConfig.class);
                return new HikariPool(config);
            })
            .id("hikari")
            .primary();
    }
}

package com.jujin.freeway.db.hikari;

import com.jujin.freeway.db.Pool;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module2;

public final class HikariPoolModule implements Module2{

    @Override
    public void bind(Binder binder) {
        binder.bind(Pool.class)
            .to(container -> {
                PoolConfig config = container.get(PoolConfig.class);
                return new HikariPool(config);
            })
            .id("hikari")
            .primary();
    }
}

package com.jujin.freeway.ioc.extension;

public interface Contribution {
    Contribution before(String... ids);

    Contribution after(String... ids);
}

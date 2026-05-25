package com.jujin.freeway.ioc.symbol;

public interface SymbolSource {
    String resolve(String name);

    String expand(String input);
}

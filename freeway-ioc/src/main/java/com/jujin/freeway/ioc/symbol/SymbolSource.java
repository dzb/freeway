package com.jujin.freeway2.ioc.symbol;

public interface SymbolSource {
    String resolve(String name);

    String expand(String input);
}

package com.jujin.freeway.db;

@FunctionalInterface
public interface Transactional {
    void run() throws Exception;
}

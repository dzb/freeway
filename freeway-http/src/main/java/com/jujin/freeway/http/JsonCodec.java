package com.jujin.freeway.http;

import java.lang.reflect.Type;

public interface JsonCodec {
    String toJson(Object value);

    <T> T fromJson(String json, Class<T> type);

    <T> T fromJson(String json, Type type);
}

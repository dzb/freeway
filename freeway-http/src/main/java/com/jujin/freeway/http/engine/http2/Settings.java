package com.jujin.freeway.http.engine.http2;

import java.util.function.Consumer;

public final class Settings {
    private final SettingParameter[] s = new SettingParameter[7];

    public SettingParameter get(SettingIdentifier id) {
        return s[id.value];
    }

    public SettingParameter getOrDefault(SettingIdentifier id, SettingParameter defaultValue) {
        var r = s[id.value];
        return r != null ? r : defaultValue;
    }

    public void set(SettingParameter p) {
        s[p.identifier.value] = p;
    }

    public void forEach(Consumer<SettingParameter> fn) {
        for (var p : s) if (p != null) fn.accept(p);
    }
}

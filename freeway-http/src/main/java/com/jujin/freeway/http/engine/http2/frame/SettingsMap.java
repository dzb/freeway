package com.jujin.freeway.http.engine.http2.frame;

import java.util.function.Consumer;

public final class SettingsMap {
    private final SettingParameter[] s = new SettingParameter[7];

    public SettingParameter get(SettingIdentifier id) {
        return s[id.value];
    }

    public SettingParameter getOrDefault(SettingIdentifier id, SettingParameter d) {
        var r = s[id.value];
        return r != null ? r : d;
    }

    public void set(SettingParameter p) {
        s[p.identifier.value] = p;
    }

    public void forEach(Consumer<SettingParameter> fn) {
        for (var p : s) if (p != null) fn.accept(p);
    }
}

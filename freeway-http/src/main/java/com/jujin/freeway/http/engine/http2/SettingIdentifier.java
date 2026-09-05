package com.jujin.freeway.http.engine.http2;

enum SettingIdentifier {
    SETTINGS_HEADER_TABLE_SIZE(0x1), SETTINGS_ENABLE_PUSH(0x2), SETTINGS_MAX_CONCURRENT_STREAMS(0x3), SETTINGS_INITIAL_WINDOW_SIZE(0x4), SETTINGS_MAX_FRAME_SIZE(0x5), SETTINGS_MAX_HEADER_LIST_SIZE(0x6), SETTINGS_NONE(0x0);
    public final int value;

    SettingIdentifier(int v) {
        value = v;
    }

    public static SettingIdentifier fromValue(int v) {
        for (var e : values()) if (e.value == v && e != SETTINGS_NONE) return e;
        return SETTINGS_NONE;
    }

    public boolean validateValue(long v) {
        return switch (this) {
            // RFC 7540 §6.5.2: SETTINGS_HEADER_TABLE_SIZE is a 32-bit
            // unsigned value — the wire parse is already unsigned, but a
            // programmatically constructed setting must not smuggle a
            // negative (or > uint32) value past validation into the HPACK
            // decoder, where it would poison the dynamic-table state.
            case SETTINGS_HEADER_TABLE_SIZE -> v >= 0 && v <= 0xFFFFFFFFL;
            case SETTINGS_INITIAL_WINDOW_SIZE -> true;
            case SETTINGS_MAX_FRAME_SIZE -> v >= 16384 && v <= 16777215;
            case SETTINGS_MAX_HEADER_LIST_SIZE -> v >= 0;
            case SETTINGS_ENABLE_PUSH -> v == 0 || v == 1;
            case SETTINGS_MAX_CONCURRENT_STREAMS -> v >= 0 && v <= 0x7FFFFFFF;
            default -> false;
        };
    }
}

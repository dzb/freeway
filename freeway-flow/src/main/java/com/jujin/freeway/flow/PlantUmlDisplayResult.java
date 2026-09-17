package com.jujin.freeway.flow;

/**
 * PlantUML display mapping result
 */
public class PlantUmlDisplayResult {
    public static final PlantUmlDisplayResult HIDDEN = new PlantUmlDisplayResult(false, null);

    public static PlantUmlDisplayResult of(String text) {
        if (text == null || text.isEmpty()) {
            return HIDDEN;
        }
        return new PlantUmlDisplayResult(true, text);
    }

    public static PlantUmlDisplayResult ofDefault() {
        return new PlantUmlDisplayResult(true, null);
    }

    private final boolean visible;
    private final String text;

    private PlantUmlDisplayResult(boolean visible, String text) {
        this.visible = visible;
        this.text = text;
    }

    public boolean isVisible() {
        return visible;
    }

    public String text() {
        return text;
    }

    public boolean isUseDefault() {
        return visible && text == null;
    }
}

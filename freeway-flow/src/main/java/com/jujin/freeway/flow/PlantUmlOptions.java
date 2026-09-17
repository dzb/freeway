package com.jujin.freeway.flow;

/**
 * PlantUML output options — an immutable value with per-field withers, in
 * line with the repo's optional-input rule (no mutable shared default).
 */
public record PlantUmlOptions(boolean showGatewayType, boolean showIdInTitle) {

    public static PlantUmlOptions defaults() {
        return new PlantUmlOptions(true, false);
    }

    public PlantUmlOptions withShowGatewayType(boolean value) {
        return showGatewayType == value ? this : new PlantUmlOptions(value, showIdInTitle);
    }

    public PlantUmlOptions withShowIdInTitle(boolean value) {
        return showIdInTitle == value ? this : new PlantUmlOptions(showGatewayType, value);
    }
}

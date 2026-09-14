package com.jujin.freeway.flow;

/**
 * PlantUML output options
 *
 * @author noear
 * @since 3.10
 */
public class PlantUmlOptions {
    public static final PlantUmlOptions DEFAULT = new PlantUmlOptions();

    private boolean showGatewayType = true;
    private boolean showIdInTitle = false;

    public boolean isShowGatewayType() {
        return showGatewayType;
    }

    public PlantUmlOptions showGatewayType(boolean showGatewayType) {
        this.showGatewayType = showGatewayType;
        return this;
    }

    public boolean isShowIdInTitle() {
        return showIdInTitle;
    }

    public PlantUmlOptions showIdInTitle(boolean showIdInTitle) {
        this.showIdInTitle = showIdInTitle;
        return this;
    }
}

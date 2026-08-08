package com.jujin.freeway.flow;

/**
 * PlantUML output options
 *
 * @author noear
 * @since 3.10
 */
public class PlantumlOptions {
    public static final PlantumlOptions DEFAULT = new PlantumlOptions();

    private boolean showGatewayType = true;
    private boolean showIdInTitle = false;

    public boolean isShowGatewayType() {
        return showGatewayType;
    }

    public PlantumlOptions showGatewayType(boolean showGatewayType) {
        this.showGatewayType = showGatewayType;
        return this;
    }

    public boolean isShowIdInTitle() {
        return showIdInTitle;
    }

    public PlantumlOptions showIdInTitle(boolean showIdInTitle) {
        this.showIdInTitle = showIdInTitle;
        return this;
    }
}

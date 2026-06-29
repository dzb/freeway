package com.jujin.freeway.flow;

/**
 * PlantUML 显示映射结果
 *
 * @author noear
 * @since 3.10
 */
public class PlantumlDisplayResult {
    public static final PlantumlDisplayResult HIDDEN = new PlantumlDisplayResult(false, null);

    public static PlantumlDisplayResult of(String text) {
        if (text == null || text.isEmpty()) {
            return HIDDEN;
        }
        return new PlantumlDisplayResult(true, text);
    }

    public static PlantumlDisplayResult ofDefault() {
        return new PlantumlDisplayResult(true, null);
    }

    private final boolean visible;
    private final String text;

    private PlantumlDisplayResult(boolean visible, String text) {
        this.visible = visible;
        this.text = text;
    }

    public boolean isVisible() {
        return visible;
    }

    public String getText() {
        return text;
    }

    public boolean isUseDefault() {
        return visible && text == null;
    }
}

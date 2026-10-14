package top.mcocet.engine;

/**
 * 归一化后的事件信号。
 * 规则引擎不直接依赖 xinbot 的事件类型,所有外部事件先在 {@code RuleListener}
 * 中被转换成 {@link Signal},再交给引擎匹配,便于扩展新的信号来源。
 */
public class Signal {

    /** 信号来源类型。 */
    public enum Type {
        SYSTEM_CHAT,
        PUBLIC_CHAT,
        PRIVATE_CHAT,
        OVERLAY,
        SERVER_CHANGE,
        DISCONNECT,
        WINDOW_OPEN
    }

    private final Type type;
    private final String text;
    private final String sender;
    private final boolean overlay;
    /** 容器/菜单窗口 id(仅 WINDOW_OPEN 有效),供容器类动作以 {{containerId}} 引用。 */
    private final Integer containerId;

    public Signal(Type type, String text) {
        this(type, text, null, false, null);
    }

    public Signal(Type type, String text, String sender, boolean overlay) {
        this(type, text, sender, overlay, null);
    }

    public Signal(Type type, String text, String sender, boolean overlay, Integer containerId) {
        this.type = type;
        this.text = text == null ? "" : text;
        this.sender = sender;
        this.overlay = overlay;
        this.containerId = containerId;
    }

    public Type getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public String getSender() {
        return sender;
    }

    public boolean isOverlay() {
        return overlay;
    }

    public Integer getContainerId() {
        return containerId;
    }

    @Override
    public String toString() {
        return "Signal{type=" + type + ", overlay=" + overlay
                + (sender != null ? ", sender=" + sender : "")
                + (containerId != null ? ", containerId=" + containerId : "")
                + ", text='" + text + "'}";
    }
}

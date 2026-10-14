package top.mcocet.engine;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则动作。一条规则命中后,按顺序执行其动作列表。
 *
 * <p>动作类型由配置里出现的键决定(互斥,自上而下优先):</p>
 * <pre>
 * 文本/会话类:
 *   command = "login {{password}}"        发送命令(可带前导 /)
 *   chat    = "hello"                     发送聊天消息
 *   stage   = "LOGGED_IN"                 切换阶段
 *   setVariable = { k = v, ... }          写入会话变量
 *   log     = "文本"                       打印日志
 *   tab     = "msg {{sender}}"            命令 Tab 补全建议(服务器命令探测)
 * 物品/容器交互类(需已连接并处于游戏):
 *   useItem      = "MAIN_HAND"            使用手持物品(触发"点击物品"类交互)
 *   selectSlot   = 3                       选择快捷栏槽位(0-8)
 *   closeContainer = 0                     关闭容器/菜单(0=玩家背包)
 *   containerButton = { containerId=0, button=1 }               点击容器按钮
 *   containerClick  = { containerId=1, slot=13, button=0, stateId=0 }  点击容器槽位
 *   custom  = "名称"                       预留:自定义动作(暂未执行)
 * </pre>
 *
 * <p>字符串字段支持 {@code {{变量}}} 插值,变量来源为会话变量 + 条件捕获组 + 凭据。</p>
 *
 * <p>注意:容器槽位点击以"尽力而为"方式发送(stateId/carriedItem 未跟踪真实背包状态),
 * 适用于服务端托管的登录/排队菜单(通常只读取 slot);对严格校验 stateId 的原生容器可能失败。</p>
 */
public class Action {

    public enum Kind {
        COMMAND, CHAT, STAGE, SET_VARIABLE, LOG, CUSTOM, NONE,
        USE_ITEM, SELECT_SLOT, CLOSE_CONTAINER, CONTAINER_BUTTON, CONTAINER_CLICK, TAB
    }

    private final Kind kind;
    private final String value;
    private final Map<String, String> variables;
    private final Map<String, Object> args;

    private Action(Kind kind, String value, Map<String, String> variables, Map<String, Object> args) {
        this.kind = kind;
        this.value = value;
        this.variables = variables;
        this.args = args;
    }

    public static Action fromConfig(Config cfg) {
        // ---- 文本/会话动作(保持原有优先顺序) ----
        if (cfg.hasPath("command")) {
            return new Action(Kind.COMMAND, cfg.getString("command"), null, null);
        }
        if (cfg.hasPath("chat")) {
            return new Action(Kind.CHAT, cfg.getString("chat"), null, null);
        }
        if (cfg.hasPath("stage")) {
            return new Action(Kind.STAGE, cfg.getString("stage"), null, null);
        }
        if (cfg.hasPath("log")) {
            return new Action(Kind.LOG, cfg.getString("log"), null, null);
        }
        if (cfg.hasPath("setVariable")) {
            Map<String, String> map = new LinkedHashMap<>();
            Config v = cfg.getConfig("setVariable");
            for (Map.Entry<String, ConfigValue> e : v.entrySet()) {
                map.put(e.getKey(), e.getValue().unwrapped() == null ? "" : String.valueOf(e.getValue().unwrapped()));
            }
            return new Action(Kind.SET_VARIABLE, null, map, null);
        }

        // ---- Tab 补全建议:tab = "命令片段" ----
        if (cfg.hasPath("tab")) {
            return new Action(Kind.TAB, cfg.getString("tab"), null, null);
        }

        // ---- 物品/容器交互动作 ----
        if (cfg.hasPath("useItem")) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("hand", parseHand(cfg.getValue("useItem").unwrapped()));
            return new Action(Kind.USE_ITEM, null, null, a);
        }
        if (cfg.hasPath("selectSlot")) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("slot", numberAt(cfg.getValue("selectSlot").unwrapped(), "slot", 0));
            return new Action(Kind.SELECT_SLOT, null, null, a);
        }
        if (cfg.hasPath("closeContainer")) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("containerId", numberAt(cfg.getValue("closeContainer").unwrapped(), "containerId", 0));
            return new Action(Kind.CLOSE_CONTAINER, null, null, a);
        }
        if (cfg.hasPath("containerButton")) {
            Config b = cfg.getConfig("containerButton");
            Map<String, Object> a = new LinkedHashMap<>();
            if (b.hasPath("containerId")) {
                a.put("containerId", b.getInt("containerId"));
            }
            a.put("button", b.hasPath("button") ? b.getInt("button") : 0);
            return new Action(Kind.CONTAINER_BUTTON, null, null, a);
        }
        if (cfg.hasPath("containerClick")) {
            Config b = cfg.getConfig("containerClick");
            Map<String, Object> a = new LinkedHashMap<>();
            if (b.hasPath("containerId")) {
                a.put("containerId", b.getInt("containerId"));
            }
            a.put("slot", b.hasPath("slot") ? b.getInt("slot") : 0);
            a.put("button", b.hasPath("button") ? b.getInt("button") : 0);
            a.put("stateId", b.hasPath("stateId") ? b.getInt("stateId") : 0);
            return new Action(Kind.CONTAINER_CLICK, null, null, a);
        }

        if (cfg.hasPath("custom")) {
            return new Action(Kind.CUSTOM, cfg.getString("custom"), null, null);
        }
        return new Action(Kind.NONE, null, null, null);
    }

    private static String parseHand(Object u) {
        if (u instanceof String s) {
            return s;
        }
        if (u instanceof Map<?, ?> m && m.get("hand") != null) {
            return String.valueOf(m.get("hand"));
        }
        return "MAIN_HAND"; // 也涵盖 useItem = true 的情况
    }

    /** 值可能是标量(Number/Boolean)或对象里的某个字段;统一解析为 int。 */
    private static int numberAt(Object u, String field, int def) {
        if (u instanceof Number n) {
            return n.intValue();
        }
        if (u instanceof Map<?, ?> m && m.get(field) instanceof Number nn) {
            return nn.intValue();
        }
        return def;
    }

    public Kind getKind() {
        return kind;
    }

    public String getValue() {
        return value;
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public Map<String, Object> getArgs() {
        return args;
    }

    public int argInt(String key, int def) {
        if (args == null) {
            return def;
        }
        Object v = args.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    /** 该动作参数里是否显式给出了某个键(用于区分"未配置"与"配置为默认值")。 */
    public boolean hasArg(String key) {
        return args != null && args.containsKey(key);
    }

    public String argString(String key, String def) {
        if (args == null) {
            return def;
        }
        Object v = args.get(key);
        return v == null ? def : String.valueOf(v);
    }
}

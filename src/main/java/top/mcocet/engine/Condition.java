package top.mcocet.engine;

import com.typesafe.config.Config;
import top.mcocet.engine.Signal.Type;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则条件(叶子 + 组合)。多个叶子约束之间是 AND 关系;
 * 通过 {@code all}/{@code any}/{@code not} 支持组合嵌套。
 *
 * <p>命中时,{@code regex} 的捕获组会写入输出变量表(g1..gN 以及命名组),
 * 供后续动作以 {@code {{g1}}} 的形式插值使用。</p>
 */
public class Condition {

    private Set<Type> types = EnumSet.noneOf(Type.class);
    private List<String> contains = new ArrayList<>();
    private List<String> notContains = new ArrayList<>();
    private String equalsText;
    private String startsWithText;
    private String regex;
    private Pattern regexPattern;
    private List<String> stage;
    private List<String> notStage;
    private String sender;
    private Boolean overlay;
    private boolean ignoreCase = true;

    private List<Condition> all;
    private List<Condition> any;
    private Condition not;

    /** 从 HOCON 配置片段构造条件;传入 {@code null} 返回"恒真"条件。 */
    public static Condition fromConfig(Config cfg) {
        Condition c = new Condition();
        if (cfg == null) {
            return c;
        }
        if (cfg.hasPath("types")) {
            for (String t : cfg.getStringList("types")) {
                try {
                    c.types.add(Type.valueOf(t.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // 未知类型名,跳过
                }
            }
        }
        if (cfg.hasPath("contains")) {
            c.contains = cfg.getStringList("contains");
        }
        if (cfg.hasPath("notContains")) {
            c.notContains = cfg.getStringList("notContains");
        }
        if (cfg.hasPath("equals")) {
            c.equalsText = cfg.getString("equals");
        }
        if (cfg.hasPath("startsWith")) {
            c.startsWithText = cfg.getString("startsWith");
        }
        if (cfg.hasPath("regex")) {
            c.regex = cfg.getString("regex");
        }
        if (cfg.hasPath("stage")) {
            c.stage = cfg.getStringList("stage");
        }
        if (cfg.hasPath("notStage")) {
            c.notStage = cfg.getStringList("notStage");
        }
        if (cfg.hasPath("sender")) {
            c.sender = cfg.getString("sender");
        }
        if (cfg.hasPath("overlay")) {
            c.overlay = cfg.getBoolean("overlay");
        }
        if (cfg.hasPath("ignoreCase")) {
            c.ignoreCase = cfg.getBoolean("ignoreCase");
        }
        if (cfg.hasPath("all")) {
            c.all = loadList(cfg.getConfigList("all"));
        }
        if (cfg.hasPath("any")) {
            c.any = loadList(cfg.getConfigList("any"));
        }
        if (cfg.hasPath("not")) {
            c.not = fromConfig(cfg.getConfig("not"));
        }

        if (c.regex != null) {
            int flags = c.ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
            c.regexPattern = Pattern.compile(c.regex, flags);
        }
        return c;
    }

    private static List<Condition> loadList(List<? extends Config> configs) {
        List<Condition> list = new ArrayList<>();
        for (Config sub : configs) {
            list.add(fromConfig(sub));
        }
        return list;
    }

    /**
     * 判断信号是否满足本条件。
     *
     * @param signal 待判定信号
     * @param ctx    会话上下文(读取当前 stage)
     * @param out    命中时收集到的捕获变量
     * @return 是否匹配
     */
    public boolean matches(Signal signal, RuleContext ctx, Map<String, String> out) {
        // 组合:not —— 用临时捕获表,避免污染 out
        if (not != null) {
            Map<String, String> scratch = new java.util.HashMap<>();
            if (not.matches(signal, ctx, scratch)) {
                return false;
            }
        }
        if (all != null) {
            for (Condition sub : all) {
                if (!sub.matches(signal, ctx, out)) {
                    return false;
                }
            }
        }
        if (any != null) {
            boolean matched = false;
            for (Condition sub : any) {
                Map<String, String> branch = new java.util.HashMap<>();
                if (sub.matches(signal, ctx, branch)) {
                    out.putAll(branch);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }

        // 类型过滤
        if (!types.isEmpty() && !types.contains(signal.getType())) {
            return false;
        }
        // overlay 过滤
        if (overlay != null && overlay != signal.isOverlay()) {
            return false;
        }
        // 阶段过滤
        if (stage != null && !stage.isEmpty()) {
            String cur = ctx.getStage() == null ? "" : ctx.getStage();
            boolean ok = stage.stream().anyMatch(s -> s.equalsIgnoreCase(cur));
            if (!ok) {
                return false;
            }
        }
        // 阶段取反过滤:当前阶段若落在 notStage 列表内则不匹配
        if (notStage != null && !notStage.isEmpty()) {
            String cur = ctx.getStage() == null ? "" : ctx.getStage();
            boolean banned = notStage.stream().anyMatch(s -> s.equalsIgnoreCase(cur));
            if (banned) {
                return false;
            }
        }
        // 发送者过滤
        if (sender != null) {
            String s = signal.getSender();
            if (s == null || !(ignoreCase ? s.equalsIgnoreCase(sender) : s.equals(sender))) {
                return false;
            }
        }

        String text = signal.getText() == null ? "" : signal.getText();
        String hay = ignoreCase ? text.toLowerCase(Locale.ROOT) : text;

        for (String needle : contains) {
            String n = ignoreCase ? needle.toLowerCase(Locale.ROOT) : needle;
            if (!hay.contains(n)) {
                return false;
            }
        }
        for (String needle : notContains) {
            String n = ignoreCase ? needle.toLowerCase(Locale.ROOT) : needle;
            if (hay.contains(n)) {
                return false;
            }
        }
        if (equalsText != null) {
            String e = ignoreCase ? equalsText.toLowerCase(Locale.ROOT) : equalsText;
            if (!hay.equals(e)) {
                return false;
            }
        }
        if (startsWithText != null) {
            String p = ignoreCase ? startsWithText.toLowerCase(Locale.ROOT) : startsWithText;
            if (!hay.startsWith(p)) {
                return false;
            }
        }
        if (regexPattern != null) {
            Matcher m = regexPattern.matcher(text);
            if (!m.find()) {
                return false;
            }
            for (int i = 1; i <= m.groupCount(); i++) {
                String v = m.group(i);
                if (v != null) {
                    out.put("g" + i, v);
                }
            }
            // 命名组无法直接枚举,但可在动作中通过已知名字引用;这里尝试常见占位
        }
        return true;
    }

    public boolean isEmpty() {
        return types.isEmpty() && contains.isEmpty() && notContains.isEmpty()
                && equalsText == null && startsWithText == null && regex == null
                && (stage == null || stage.isEmpty()) && (notStage == null || notStage.isEmpty())
                && sender == null && overlay == null
                && all == null && any == null && not == null;
    }
}

package top.mcocet.engine;

import com.typesafe.config.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条规则 = 触发条件({@link Condition}) + 动作序列({@link Action}) + 执行修饰。
 *
 * <p>修饰字段:</p>
 * <ul>
 *   <li>{@code priority} 数值越小越先评估(默认 100)</li>
 *   <li>{@code once} 整场会话仅触发一次(默认 false)</li>
 *   <li>{@code cooldownMs} 两次触发间的最小间隔毫秒(默认 0,不冷却)</li>
 *   <li>{@code stopOnMatch} 命中后终止后续规则评估(默认 false)</li>
 * </ul>
 */
public class Rule {

    private final String id;
    private final int priority;
    private final boolean once;
    private final long cooldownMs;
    private final boolean stopOnMatch;
    private final Condition condition;
    private final List<Action> actions;

    private Rule(String id, int priority, boolean once, long cooldownMs,
                 boolean stopOnMatch, Condition condition, List<Action> actions) {
        this.id = id;
        this.priority = priority;
        this.once = once;
        this.cooldownMs = cooldownMs;
        this.stopOnMatch = stopOnMatch;
        this.condition = condition;
        this.actions = actions;
    }

    public static Rule fromConfig(Config cfg, int index) {
        String id = cfg.hasPath("id") ? cfg.getString("id") : ("rule-" + index);
        int priority = cfg.hasPath("priority") ? cfg.getInt("priority") : 100;
        boolean once = cfg.hasPath("once") && cfg.getBoolean("once");
        long cooldownMs = cfg.hasPath("cooldownMs") ? cfg.getLong("cooldownMs") : 0L;
        boolean stopOnMatch = cfg.hasPath("stopOnMatch") && cfg.getBoolean("stopOnMatch");

        Condition condition = cfg.hasPath("when")
                ? Condition.fromConfig(cfg.getConfig("when"))
                : Condition.fromConfig(null);

        List<Action> actions = new ArrayList<>();
        if (cfg.hasPath("actions")) {
            for (Config a : cfg.getConfigList("actions")) {
                actions.add(Action.fromConfig(a));
            }
        } else if (cfg.hasPath("action")) {
            actions.add(Action.fromConfig(cfg.getConfig("action")));
        }
        return new Rule(id, priority, once, cooldownMs, stopOnMatch, condition, actions);
    }

    public String getId() {
        return id;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isOnce() {
        return once;
    }

    public long getCooldownMs() {
        return cooldownMs;
    }

    public boolean isStopOnMatch() {
        return stopOnMatch;
    }

    public Condition getCondition() {
        return condition;
    }

    public List<Action> getActions() {
        return actions;
    }
}

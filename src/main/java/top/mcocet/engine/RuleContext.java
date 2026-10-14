package top.mcocet.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 会话级上下文:保存当前阶段、变量、已触发的 once 规则、冷却时间戳。
 * 每次(重)连接应重建一个实例,使状态机回到初始阶段。
 */
public class RuleContext {

    public static final String INITIAL_STAGE = "INIT";

    private String stage = INITIAL_STAGE;
    private final Map<String, String> variables = new HashMap<>();
    private final Set<String> executedOnce = new HashSet<>();
    private final Map<String, Long> cooldown = new HashMap<>();

    public RuleContext(Map<String, String> seedVariables) {
        if (seedVariables != null) {
            variables.putAll(seedVariables);
        }
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public void setVariable(String key, String value) {
        variables.put(key, value);
    }

    public boolean isExecutedOnce(String ruleId) {
        return executedOnce.contains(ruleId);
    }

    public void markExecutedOnce(String ruleId) {
        executedOnce.add(ruleId);
    }

    public boolean isOnCooldown(String ruleId, long cooldownMs) {
        if (cooldownMs <= 0) {
            return false;
        }
        Long last = cooldown.get(ruleId);
        return last != null && (System.currentTimeMillis() - last) < cooldownMs;
    }

    public void touchCooldown(String ruleId) {
        cooldown.put(ruleId, System.currentTimeMillis());
    }
}

package top.mcocet.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录规则引擎。按 priority 升序评估规则,命中即执行动作,并受 once / cooldown /
 * stopOnMatch 修饰控制。引擎本身不关心事件来源,只消费 {@link Signal}。
 */
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger("XinConnect");

    private volatile List<Rule> rules = new ArrayList<>();
    private volatile Map<String, String> seedVariables = new HashMap<>();
    private volatile boolean debug = false;
    private final ActionExecutor actionExecutor = new ActionExecutor();
    private volatile RuleContext context = new RuleContext(seedVariables);

    /** 加载/重载规则集合与会话初始变量,并重置上下文。 */
    public synchronized void reload(List<Rule> newRules, Map<String, String> seedVars, boolean debug) {
        List<Rule> sorted = new ArrayList<>(newRules == null ? List.of() : newRules);
        sorted.sort(Comparator.comparingInt(Rule::getPriority));
        this.rules = sorted;
        this.seedVariables = seedVars == null ? new HashMap<>() : new HashMap<>(seedVars);
        this.debug = debug;
        this.context = new RuleContext(this.seedVariables);
        log.info("[XinConnect] 规则已加载:{} 条规则,阶段={},调试={}", sorted.size(), context.getStage(), debug);
    }

    /** 会话(重)连接时重置上下文,回到初始阶段。 */
    public void reset() {
        this.context = new RuleContext(this.seedVariables);
        log.info("[XinConnect] 会话上下文已重置,阶段回到 {}", context.getStage());
    }

    /** 处理一个信号:依次评估所有规则。 */
    public void handle(Signal signal) {
        if (signal == null || rules.isEmpty()) {
            return;
        }
        if (debug) {
            log.info("[XinConnect][debug] 收到信号 {}", signal);
        }
        RuleContext ctx = this.context;
        for (Rule rule : rules) {
            Map<String, String> captures = new HashMap<>();
            if (!rule.getCondition().matches(signal, ctx, captures)) {
                continue;
            }
            if (rule.isOnce() && ctx.isExecutedOnce(rule.getId())) {
                if (debug) {
                    log.info("[XinConnect][debug] 规则 '{}' 已 once 触发过,跳过", rule.getId());
                }
                continue;
            }
            if (ctx.isOnCooldown(rule.getId(), rule.getCooldownMs())) {
                if (debug) {
                    log.info("[XinConnect][debug] 规则 '{}' 处于冷却,跳过", rule.getId());
                }
                continue;
            }

            log.debug("[XinConnect] 命中规则 '{}' <- {}", rule.getId(), signal);
            Map<String, String> vars = new HashMap<>(ctx.getVariables());
            vars.putAll(captures);
            // 注入信号上下文变量,便于动作插值/调试
            vars.put("text", signal.getText());
            vars.put("type", signal.getType().name());
            vars.put("stage", ctx.getStage() == null ? "" : ctx.getStage());
            if (signal.getSender() != null) {
                vars.put("sender", signal.getSender());
            }
            vars.put("overlay", String.valueOf(signal.isOverlay()));
            // 容器窗口:把最近一次打开的 containerId 记入会话变量,后续容器动作可用 {{containerId}} 引用
            if (signal.getContainerId() != null) {
                ctx.setVariable("containerId", String.valueOf(signal.getContainerId()));
                vars.put("containerId", String.valueOf(signal.getContainerId()));
            }
            for (Action action : rule.getActions()) {
                try {
                    actionExecutor.execute(action, vars, ctx);
                } catch (Exception e) {
                    log.error("[XinConnect] 规则 '{}' 动作执行失败", rule.getId(), e);
                }
            }
            ctx.markExecutedOnce(rule.getId());
            ctx.touchCooldown(rule.getId());

            if (rule.isStopOnMatch()) {
                break;
            }
        }
    }

    /** 供调试命令使用:模拟一个系统聊天信号。 */
    public int simulateSystemChat(String text) {
        Signal signal = new Signal(Signal.Type.SYSTEM_CHAT, text, null, false);
        int before = context.getVariables().size();
        handle(signal);
        return context.getVariables().size() - before;
    }

    public String currentStage() {
        return context.getStage();
    }

    public int ruleCount() {
        return rules.size();
    }
}

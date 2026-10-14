package top.mcocet.engine;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内置登录流程模板库。
 *
 * <p>模板本质是"一组预置规则",其动作通过 {@code {{password}}} / {@code {{email}}}
 * 等占位符引用会话变量(来源见 {@code credentials} 与 {@code variables})。因此选用一个
 * 模板只需在配置里写 {@code templates = ["authme"]},无需重复编写规则正文——这正是
 * "不必为每个服务器单独写代码"的落地方式。</p>
 *
 * <p>模板与用户自定义 {@code rules} 会合并,统一按 {@code priority} 排序评估。
 * 若模板规则不满足需求,用户可用同名更高/更低优先级规则覆盖或补充。</p>
 */
public final class TemplateLibrary {

    private static final Logger log = LoggerFactory.getLogger("XinConnect");

    private static final String AUTHME = """
            rules = [
              {
                id = "tpl-authme-login"
                priority = 10
                cooldownMs = 4000
                notStage = ["LOGGED_IN"]
                when {
                  types = ["SYSTEM_CHAT", "PUBLIC_CHAT", "PRIVATE_CHAT"]
                  contains = ["Please log in", "you need to log in", "/login"]
                  notContains = ["logged in", "successfully", "registered"]
                }
                actions = [ { command = "login {{password}}" } ]
              }
              {
                id = "tpl-authme-register"
                priority = 20
                once = true
                when {
                  types = ["SYSTEM_CHAT", "PUBLIC_CHAT", "PRIVATE_CHAT"]
                  contains = ["Unknown account", "not registered", "/register"]
                }
                actions = [ { command = "register {{password}} {{password}}" } ]
              }
              {
                id = "tpl-authme-done"
                priority = 5
                when {
                  types = ["SYSTEM_CHAT"]
                  regex = "(?i)(logged in|authentication successful|you are now logged in)"
                }
                actions = [ { stage = "LOGGED_IN" }, { log = "authme 模板:登录完成" } ]
              }
            ]
            """
            ;

    private static final String REGISTER_THEN_LOGIN = """
            rules = [
              {
                id = "tpl-reg-then-login-register"
                priority = 20
                once = true
                when {
                  types = ["SYSTEM_CHAT", "PUBLIC_CHAT", "PRIVATE_CHAT"]
                  contains = ["Unknown account", "not registered", "/register"]
                }
                actions = [ { command = "register {{email}} {{password}} {{password}}" } ]
              },
              {
                id = "tpl-reg-then-login-login"
                priority = 10
                cooldownMs = 4000
                notStage = ["LOGGED_IN"]
                when {
                  types = ["SYSTEM_CHAT", "PUBLIC_CHAT", "PRIVATE_CHAT"]
                  contains = ["Please log in", "/login"]
                  notContains = ["logged in", "registered"]
                }
                actions = [ { command = "login {{password}}" } ]
              },
              {
                id = "tpl-reg-then-login-done"
                priority = 5
                when {
                  types = ["SYSTEM_CHAT"]
                  regex = "(?i)(logged in|registered successfully|you are now logged in)"
                }
                actions = [ { stage = "LOGGED_IN" } ]
              }
            ]
            """
            ;

    private static final String JOIN_LOGIN_FALLBACK = """
            rules = [
              {
                id = "tpl-join-fallback"
                priority = 60
                cooldownMs = 8000
                notStage = ["LOGGED_IN"]
                when {
                  types = ["SERVER_CHANGE"]
                  equals = "GAME"
                }
                actions = [ { command = "login {{password}}" } ]
              }
            ]
            """
            ;

    private static final String QUEUE_JOIN_ITEM = """
            rules = [
              {
                id = "tpl-queue-select-item"
                priority = 40
                cooldownMs = 3000
                when {
                  types = ["SYSTEM_CHAT", "OVERLAY"]
                  contains = ["queue", "right click", "hold", "to join", "点击", "加入"]
                }
                actions = [
                  { selectSlot = 0 },
                  { useItem = "MAIN_HAND" }
                ]
              }
            ]
            """
            ;

    private static final Set<String> NAMES = new LinkedHashSet<>(
            List.of("authme", "register-then-login", "join-login-fallback", "queue-join-item"));

    private TemplateLibrary() {
    }

    /** 已注册的模板名集合。 */
    public static Set<String> names() {
        return new LinkedHashSet<>(NAMES);
    }

    /** 展开指定模板为规则列表;未知模板名返回空列表并告警。 */
    public static List<Rule> expand(String name) {
        String hocon = hoconFor(name);
        if (hocon == null) {
            log.warn("[XinConnect] 未知模板:{},已忽略(可用:{})", name, names());
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        try {
            Config cfg = ConfigFactory.parseString(hocon);
            int i = 0;
            for (Config rc : cfg.getConfigList("rules")) {
                rules.add(Rule.fromConfig(rc, i++));
            }
        } catch (Exception e) {
            log.error("[XinConnect] 模板解析失败:{}", name, e);
        }
        return rules;
    }

    private static String hoconFor(String name) {
        return switch (name == null ? "" : name) {
            case "authme" -> AUTHME;
            case "register-then-login" -> REGISTER_THEN_LOGIN;
            case "join-login-fallback" -> JOIN_LOGIN_FALLBACK;
            case "queue-join-item" -> QUEUE_JOIN_ITEM;
            default -> null;
        };
    }
}

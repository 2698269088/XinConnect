package top.mcocet.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;
import top.mcocet.engine.Rule;
import top.mcocet.engine.TemplateLibrary;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XinConnect 插件独立配置文件(xinconnect.conf)的解析结果。
 *
 * <p>结构约定(全部可读、除 host 外都有合理默认):</p>
 * <pre>
 * connection { host = "...", port = 25565 }
 * credentials { password = "...", email = "...", username = "..." }
 * variables   { 任意自定义键 = "值" }
 * debug       = false
 * templates   = ["authme", ...]        # 选用内置流程模板,会自动展开成规则
 * rules = [ { id=..., when={...}, actions=[{...}] }, ... ]
 * </pre>
 */
public class XinConnectConfig {

    private final String host;
    private final int port;
    private final boolean debug;
    private final Map<String, String> credentials;
    private final Map<String, String> variables;
    private final List<Rule> rules;

    private XinConnectConfig(String host, int port, boolean debug, Map<String, String> credentials,
                             Map<String, String> variables, List<Rule> rules) {
        this.host = host;
        this.port = port;
        this.debug = debug;
        this.credentials = credentials;
        this.variables = variables;
        this.rules = rules;
    }

    public static XinConnectConfig load(File file) {
        Config root = ConfigFactory.parseFile(file).resolve();

        String host = root.hasPath("connection.host") ? root.getString("connection.host").trim() : "";
        int port = root.hasPath("connection.port") ? root.getInt("connection.port") : 25565;
        boolean debug = root.hasPath("debug") && root.getBoolean("debug");

        Map<String, String> credentials = readFlat(root, "credentials");
        Map<String, String> variables = readFlat(root, "variables");

        // 先展开模板,再拼接用户自定义规则;统一交给引擎按优先级排序
        List<Rule> rules = new ArrayList<>();
        if (root.hasPath("templates")) {
            for (String name : root.getStringList("templates")) {
                rules.addAll(TemplateLibrary.expand(name.trim()));
            }
        }
        if (root.hasPath("rules")) {
            int i = 0;
            for (Config rc : root.getConfigList("rules")) {
                rules.add(Rule.fromConfig(rc, i++));
            }
        }

        return new XinConnectConfig(host, port, debug, credentials, variables, rules);
    }

    private static Map<String, String> readFlat(Config root, String path) {
        Map<String, String> map = new LinkedHashMap<>();
        if (root.hasPath(path)) {
            Config sub = root.getConfig(path);
            for (Map.Entry<String, ConfigValue> e : sub.entrySet()) {
                Object v = e.getValue().unwrapped();
                map.put(e.getKey(), v == null ? "" : String.valueOf(v));
            }
        }
        return map;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isDebug() {
        return debug;
    }

    public Map<String, String> getCredentials() {
        return credentials;
    }

    public List<Rule> getRules() {
        return rules;
    }

    /** 会话初始变量:credentials 与 variables 合并(后者优先),再加常用别名。 */
    public Map<String, String> buildSeedVariables(String botName) {
        Map<String, String> seed = new LinkedHashMap<>();
        seed.putAll(credentials);
        seed.putAll(variables);
        if (botName != null && !botName.isBlank()) {
            seed.put("bot", botName);
            seed.put("username", botName);
        }
        return seed;
    }
}

package top.mcocet.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;
import top.mcocet.XinConnect;
import top.mcocet.config.XinConnectConfig;
import top.mcocet.engine.TemplateLibrary;

/**
 * 控制台命令 {@code xinconnect}(别名 xc / xconnect)。
 *
 * <pre>
 *   xinconnect reload          重新读取 xinconnect.conf 并热更新规则
 *   xinconnect status          显示目标地址、规则数量、当前阶段
 *   xinconnect try &lt;文本&gt;      用给定文本模拟一条系统聊天信号,便于调试规则
 * </pre>
 */
public class XinConnectCommandExecutor extends CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger("XinConnect");

    @Override
    public void onCommand(Command command, String label, String[] args) {
        XinConnect plugin = XinConnect.INSTANCE;
        if (plugin == null) {
            log.warn("[XinConnect] 插件尚未加载,命令不可用");
            return;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "reload" -> {
                plugin.reload();
                log.info("[XinConnect] 配置已重新加载");
            }
            case "status" -> printStatus(plugin);
            case "templates" -> log.info("[XinConnect] 可用内置模板:{}", TemplateLibrary.names());
            case "try" -> {
                if (args.length < 2) {
                    log.warn("[XinConnect] 用法:xinconnect try <文本>");
                    return;
                }
                String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                plugin.getEngine().simulateSystemChat(text);
                log.info("[XinConnect] 已模拟信号文本(当前阶段={})", plugin.getEngine().currentStage());
            }
            default -> log.warn("[XinConnect] 未知子命令:{},可用:reload / status / try", sub);
        }
    }

    private void printStatus(XinConnect plugin) {
        XinConnectConfig cfg = plugin.getConfig();
        if (cfg == null) {
            log.warn("[XinConnect] 配置未就绪");
            return;
        }
        log.info("[XinConnect] 目标 = {}:{},规则 = {} 条,当前阶段 = {},调试 = {}",
                cfg.getHost(), cfg.getPort(), cfg.getRules().size(),
                plugin.getEngine().currentStage(), cfg.isDebug());
    }
}

package top.mcocet;

import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Server;
import xin.bbtt.mcbot.Xinbot;
import xin.bbtt.mcbot.plugin.MetaPlugin;
import top.mcocet.config.XinConnectConfig;
import top.mcocet.engine.RuleEngine;
import top.mcocet.listener.RuleListener;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * XinConnect:面向通用服务器的 xinbot 元插件(MetaPlugin)+ 登录规则引擎。
 *
 * <p>职责边界:本元插件只负责"连接层"——从插件自己的配置文件 {@code xinconnect.conf}
 * 读取服务器地址,并把登录后统一判定为 {@link Server#Game}(单阶段直连)。
 * 排队 / register / login / 验证等差异化登录流程,全部交给可配置的规则引擎处理,
 * 从而无需为每个服务器单独编写代码。</p>
 */
public class XinConnect implements MetaPlugin {

    private static final Logger log = LoggerFactory.getLogger("XinConnect");
    private static final String CONFIG_FILE_NAME = "xinconnect.conf";

    /** 供命令执行器在无 DI 的情况下回连插件实例。 */
    public static volatile XinConnect INSTANCE;

    private final RuleEngine engine = new RuleEngine();
    private volatile XinConnectConfig config;
    private RuleListener listener;

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onLoad() {
        INSTANCE = this;
        File cfgFile = resolveConfigFile();
        if (!cfgFile.exists()) {
            copyDefaultConfig(cfgFile);
        }
        loadConfig(cfgFile);
    }

    @Override
    public void onEnable() {
        engine.reload(config.getRules(), config.buildSeedVariables(currentBotName()), config.isDebug());
        listener = new RuleListener(engine);
        Bot.INSTANCE.getPluginManager().registerEvents(listener, this);
        log.info("[XinConnect] 已启用,目标服务器 = {}:{}", config.getHost(), config.getPort());
    }

    @Override
    public void onDisable() {
        // 事件注册由核心 PluginManager.disablePlugin 统一注销,这里无需处理
    }

    @Override
    public void onUnload() {
        if (INSTANCE == this) {
            INSTANCE = null;
        }
    }

    // ------------------------------------------------------------------ MetaPlugin 契约

    @Override
    public SocketAddress getServerSocketAddress() {
        String host = config.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException(
                    "XinConnect:未在 " + CONFIG_FILE_NAME + " 中配置 connection.host");
        }
        return new InetSocketAddress(host, config.getPort());
    }

    /**
     * 通用元插件采用"单阶段直连":收到登录包即视为已进入游戏阶段。
     * 返回非空是必须的,否则核心 {@code Bot.connect()} 会一直等待直到连接超时而反复重连。
     */
    @Override
    public Server getServer(ClientboundLoginPacket loginPacket) {
        return Server.Game;
    }

    // ------------------------------------------------------------------ 对外能力(命令使用)

    /** 重新加载配置文件与规则。 */
    public synchronized void reload() {
        File cfgFile = resolveConfigFile();
        if (!cfgFile.exists()) {
            copyDefaultConfig(cfgFile);
        }
        loadConfig(cfgFile);
        engine.reload(config.getRules(), config.buildSeedVariables(currentBotName()), config.isDebug());
    }

    public RuleEngine getEngine() {
        return engine;
    }

    public XinConnectConfig getConfig() {
        return config;
    }

    // ------------------------------------------------------------------ 内部辅助

    private void loadConfig(File file) {
        try {
            this.config = XinConnectConfig.load(file);
            log.info("[XinConnect] 已读取配置:{},规则 {} 条", file.getAbsolutePath(), config.getRules().size());
        } catch (Exception e) {
            log.error("[XinConnect] 配置解析失败:{}", file.getAbsolutePath(), e);
            throw e;
        }
    }

    /** 配置文件优先与 xinbot 主配置同目录,否则退回当前工作目录。 */
    private File resolveConfigFile() {
        String corePath = Xinbot.configPath;
        if (corePath != null && !corePath.isBlank()) {
            Path parent = Paths.get(corePath).toAbsolutePath().getParent();
            if (parent != null) {
                return parent.resolve(CONFIG_FILE_NAME).toFile();
            }
        }
        return Paths.get(CONFIG_FILE_NAME).toAbsolutePath().toFile();
    }

    private void copyDefaultConfig(File target) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE_NAME)) {
            if (is == null) {
                log.error("[XinConnect] 内置默认配置缺失,请手动创建 {}", target.getAbsolutePath());
                return;
            }
            Path parent = target.toPath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(is, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.warn("[XinConnect] 已生成默认配置 {},请编辑其中的 connection.host 与 credentials 后重启",
                    target.getAbsolutePath());
        } catch (Exception e) {
            log.error("[XinConnect] 生成默认配置失败", e);
        }
    }

    private String currentBotName() {
        try {
            if (Bot.INSTANCE.getProtocol() != null && Bot.INSTANCE.getProtocol().getProfile() != null) {
                return Bot.INSTANCE.getProtocol().getProfile().getName();
            }
        } catch (Exception ignored) {
            // 协议尚未就绪,忽略
        }
        return null;
    }
}

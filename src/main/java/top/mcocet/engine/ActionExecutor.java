package top.mcocet.engine;

import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundCommandSuggestionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerButtonClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.Bot;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行规则动作。把配置里的抽象动作翻译成对 xinbot {@link Bot} 与底层 session 的实际调用。
 *
 * <p>文本/会话类动作(命令、聊天)统一走 {@code Bot.sendCommand}/{@code Bot.sendChatMessage},
 * 它们会进入 xinbot 的待发送队列,由核心的 {@code MessageSender} 在网络就绪且处于 GAME 状态时发出,
 * 从而避免在登录包尚未处理完时直接发包导致的时序问题。</p>
 *
 * <p>物品/容器交互类动作(使用物品、切换槽位、点击容器)无法走文本队列,直接通过
 * {@code Bot.getSession().send(packet)} 发送 serverbound 包;因此发送前必须确认
 * 会话已建立且协议双向状态均为 {@code GAME},否则会静默丢弃(避免崩在连接早期)。</p>
 */
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger("XinConnect");
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    public void execute(Action action, Map<String, String> vars, RuleContext ctx) {
        switch (action.getKind()) {
            case COMMAND -> {
                String cmd = stripSlash(interpolate(action.getValue(), vars)).trim();
                if (!cmd.isEmpty()) {
                    Bot.INSTANCE.sendCommand(cmd);
                    log.info("[XinConnect] 规则动作 -> 发送命令: /{}", cmd);
                }
            }
            case CHAT -> {
                String msg = interpolate(action.getValue(), vars);
                if (!msg.isBlank()) {
                    Bot.INSTANCE.sendChatMessage(msg);
                    log.info("[XinConnect] 规则动作 -> 发送消息: {}", msg);
                }
            }
            case STAGE -> {
                String stage = interpolate(action.getValue(), vars).trim();
                ctx.setStage(stage);
                log.info("[XinConnect] 规则动作 -> 切换阶段: {}", stage);
            }
            case SET_VARIABLE -> {
                for (Map.Entry<String, String> e : action.getVariables().entrySet()) {
                    String value = interpolate(e.getValue(), vars);
                    ctx.setVariable(e.getKey(), value);
                }
            }
            case LOG -> log.info("[XinConnect] {}", interpolate(action.getValue(), vars));
            case CUSTOM -> log.warn("[XinConnect] 自定义动作暂未支持: {}", action.getValue());
            case TAB -> doTab(action, vars);
            case USE_ITEM -> doUseItem(action, vars);
            case SELECT_SLOT -> doSelectSlot(action);
            case CLOSE_CONTAINER -> doCloseContainer(action, vars);
            case CONTAINER_BUTTON -> doContainerButton(action, vars);
            case CONTAINER_CLICK -> doContainerClick(action, vars);
            case NONE -> {
                // 无动作,忽略
            }
        }
    }

    // ---- 物品/容器交互:直接发包,发送前须处于 GAME 状态 ----

    /** 命令 Tab 补全建议:用于让服务器回送命令/占位符补全,可配合规则探测可用命令。 */
    private void doTab(Action action, Map<String, String> vars) {
        String text = interpolate(action.getValue(), vars);
        ClientSession session = gameSession("tab");
        if (session == null) {
            return;
        }
        int tx = Bot.INSTANCE.getAndIncreaseSequence();
        session.send(new ServerboundCommandSuggestionPacket(tx, text));
        log.info("[XinConnect] 规则动作 -> Tab 补全请求(tx={}): {}", tx, text);
    }

    /** 使用手持物品(主手/副手),对应"点击物品离开排队"这类交互。 */
    private void doUseItem(Action action, Map<String, String> vars) {
        ClientSession session = gameSession("useItem");
        if (session == null) {
            return;
        }
        Hand hand = parseHand(action.argString("hand", "MAIN_HAND"));
        int seq = Bot.INSTANCE.getAndIncreaseSequence();
        session.send(new ServerboundUseItemPacket(hand, seq, 0.0f, 0.0f));
        log.info("[XinConnect] 规则动作 -> 使用物品: {}(sequence={})", hand, seq);
    }

    /** 选择快捷栏槽位(0-8)。 */
    private void doSelectSlot(Action action) {
        ClientSession session = gameSession("selectSlot");
        if (session == null) {
            return;
        }
        int slot = clampSlot(action.argInt("slot", 0));
        session.send(new ServerboundSetCarriedItemPacket(slot));
        log.info("[XinConnect] 规则动作 -> 选择快捷栏槽位: {}", slot);
    }

    /** 关闭容器/菜单(未显式指定时关闭最近打开的窗口,否则为玩家背包 0)。 */
    private void doCloseContainer(Action action, Map<String, String> vars) {
        ClientSession session = gameSession("closeContainer");
        if (session == null) {
            return;
        }
        int containerId = resolveContainerId(action, vars, 0);
        session.send(new ServerboundContainerClosePacket(containerId));
        log.info("[XinConnect] 规则动作 -> 关闭容器: containerId={}", containerId);
    }

    /** 点击容器按钮(如附魔台、陷阱箱等按钮式容器)。 */
    private void doContainerButton(Action action, Map<String, String> vars) {
        ClientSession session = gameSession("containerButton");
        if (session == null) {
            return;
        }
        int containerId = resolveContainerId(action, vars, 0);
        int button = action.argInt("button", 0);
        session.send(new ServerboundContainerButtonClickPacket(containerId, button));
        log.info("[XinConnect] 规则动作 -> 点击容器按钮: containerId={}, button={}", containerId, button);
    }

    /**
     * 点击容器槽位(尽力而为)。以 {@code CLICK_ITEM} 动作发送,button=0 左键、1 右键;
     * stateId/carriedItem 未跟踪真实背包状态,适用于服务端托管的登录/排队菜单。
     */
    private void doContainerClick(Action action, Map<String, String> vars) {
        ClientSession session = gameSession("containerClick");
        if (session == null) {
            return;
        }
        int containerId = resolveContainerId(action, vars, 0);
        int slot = action.argInt("slot", 0);
        int button = action.argInt("button", 0);
        int stateId = action.argInt("stateId", 0);
        ClickItemAction click = ClickItemAction.from(button == 1 ? 1 : 0);
        Map<Integer, HashedStack> changedSlots = new HashMap<>();
        session.send(new ServerboundContainerClickPacket(
                containerId, stateId, slot, ContainerActionType.CLICK_ITEM, click, null, changedSlots));
        log.info("[XinConnect] 规则动作 -> 点击容器槽位: containerId={}, slot={}, button={}({})",
                containerId, slot, button, click);
    }

    /** 解析容器 id:显式配置优先,否则用最近打开窗口记入的 {{containerId}},再否则用默认值。 */
    private int resolveContainerId(Action action, Map<String, String> vars, int def) {
        if (action.hasArg("containerId")) {
            return action.argInt("containerId", def);
        }
        String tracked = vars == null ? null : vars.get("containerId");
        if (tracked != null) {
            try {
                return Integer.parseInt(tracked.trim());
            } catch (NumberFormatException ignored) {
                // 变量非数字,回退默认
            }
        }
        return def;
    }

    /**
     * 返回一个可安全发送 in-game 包的会话;若未连接或协议状态未到 GAME,返回 {@code null} 并打印跳过原因。
     */
    private ClientSession gameSession(String actionName) {
        ClientSession session = Bot.INSTANCE.getSession();
        if (session == null) {
            log.warn("[XinConnect] 动作 {} 已跳过:会话尚未建立", actionName);
            return null;
        }
        try {
            if (Bot.INSTANCE.getProtocol() == null
                    || Bot.INSTANCE.getProtocol().getOutboundState() != ProtocolState.GAME
                    || Bot.INSTANCE.getProtocol().getInboundState() != ProtocolState.GAME) {
                log.warn("[XinConnect] 动作 {} 已跳过:尚未进入游戏状态(ProtocolState != GAME)", actionName);
                return null;
            }
        } catch (Exception e) {
            log.warn("[XinConnect] 动作 {} 已跳过:状态检查失败({})", actionName, e.getMessage());
            return null;
        }
        return session;
    }

    private static Hand parseHand(String s) {
        if (s == null) {
            return Hand.MAIN_HAND;
        }
        try {
            return Hand.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return s.trim().equalsIgnoreCase("off") || s.trim().equalsIgnoreCase("offhand")
                    ? Hand.OFF_HAND : Hand.MAIN_HAND;
        }
    }

    private static int clampSlot(int slot) {
        if (slot < 0) {
            return 0;
        }
        return Math.min(slot, 8);
    }

    private String stripSlash(String s) {
        if (s.startsWith("/")) {
            return s.substring(1);
        }
        return s;
    }

    /** 将 {@code {{name}}} 用变量表替换;未知变量保留原样。 */
    public static String interpolate(String template, Map<String, String> vars) {
        if (template == null || vars == null || template.isEmpty()) {
            return template;
        }
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String repl = vars.get(key);
            // 未解析到的变量保留原始占位符
            m.appendReplacement(sb, Matcher.quoteReplacement(repl != null ? repl : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}

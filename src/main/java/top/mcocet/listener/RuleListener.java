package top.mcocet.listener;

import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import xin.bbtt.mcbot.Server;
import xin.bbtt.mcbot.Utils;
import xin.bbtt.mcbot.event.EventHandler;
import xin.bbtt.mcbot.event.EventPriority;
import xin.bbtt.mcbot.event.Listener;
import xin.bbtt.mcbot.events.DisconnectEvent;
import xin.bbtt.mcbot.events.OverlayUpdateEvent;
import xin.bbtt.mcbot.events.PrivateChatEvent;
import xin.bbtt.mcbot.events.PublicChatEvent;
import xin.bbtt.mcbot.events.ReceivePacketEvent;
import xin.bbtt.mcbot.events.ServerChangeEvent;
import xin.bbtt.mcbot.events.SystemChatMessageEvent;
import top.mcocet.engine.RuleEngine;
import top.mcocet.engine.Signal;

import java.util.regex.Pattern;

/**
 * 事件 -> 信号归一化适配层。把 xinbot 的各类事件转换成统一的 {@link Signal}
 * 交给 {@link RuleEngine},并对颜色码做剥离,保证规则里用纯文本匹配。
 */
public class RuleListener implements Listener {

    private static final Pattern COLOR_CODE = Pattern.compile("(?i)§[0-9a-fk-or]");

    private final RuleEngine engine;

    public RuleListener(RuleEngine engine) {
        this.engine = engine;
    }

    private static String strip(String s) {
        return s == null ? "" : COLOR_CODE.matcher(s).replaceAll("");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSystemChat(SystemChatMessageEvent event) {
        Signal signal = new Signal(Signal.Type.SYSTEM_CHAT, strip(event.getText()), null, event.isOverlay());
        engine.handle(signal);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOverlay(OverlayUpdateEvent event) {
        Signal signal = new Signal(Signal.Type.OVERLAY, strip(event.getText()), null, true);
        engine.handle(signal);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPublicChat(PublicChatEvent event) {
        GameProfile sender = event.getSender();
        String name = sender != null ? sender.getName() : null;
        Signal signal = new Signal(Signal.Type.PUBLIC_CHAT, strip(event.getMessage()), name, false);
        engine.handle(signal);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrivateChat(PrivateChatEvent event) {
        GameProfile sender = event.getSender();
        String name = sender != null ? sender.getName() : null;
        Signal signal = new Signal(Signal.Type.PRIVATE_CHAT, strip(event.getMessage()), name, false);
        engine.handle(signal);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerChange(ServerChangeEvent event) {
        Server server = event.getServer();
        if (server == null) {
            return;
        }
        String stage = server == Server.Game ? "GAME" : "LOGIN";
        Signal signal = new Signal(Signal.Type.SERVER_CHANGE, stage, null, false);
        engine.handle(signal);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisconnect(DisconnectEvent event) {
        // 断开后重置状态机,以便重连时重新走登录流程
        engine.reset();
    }

    /**
     * 监听服务器打开容器/菜单的包,转为 WINDOW_OPEN 信号。
     * 信号的 text 为菜单标题(可用于匹配),并把 containerId 记入会话变量,
     * 使后续容器类动作可直接用 {{containerId}} 定位当前窗口。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWindowOpen(ReceivePacketEvent<ClientboundOpenScreenPacket> event) {
        ClientboundOpenScreenPacket packet = event.getPacket();
        if (packet == null) {
            return;
        }
        String title = packet.getTitle() == null ? packet.getName() : strip(Utils.toString(packet.getTitle()));
        Signal signal = new Signal(Signal.Type.WINDOW_OPEN, title, null, false, packet.getContainerId());
        engine.handle(signal);
    }
}

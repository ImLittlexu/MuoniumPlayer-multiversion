package com.muoniumplayer.core;

import com.muoniumplayer.api.enums.EnumChatColor;

/** 按连接去重，维度切换和重生不重复打印入场提示。 */
public final class MusicKeybindHint {
    private Object announcedConnection;

    public boolean shouldAnnounce(Object connection, boolean playerReady) {
        if (connection == null) {
            announcedConnection = null;
            return false;
        }
        if (!playerReady || connection == announcedConnection) return false;
        announcedConnection = connection;
        return true;
    }

    public static String message(String musicKey, String hudKey) {
        return EnumChatColor.AQUA + "[MuoniumPlayer] "
                + action(musicKey, "打开音乐界面") + EnumChatColor.WHITE + "，"
                + action(hudKey, "编辑 HUD 位置") + EnumChatColor.WHITE + "。";
    }

    private static String action(String key, String action) {
        if (key == null || key.isBlank()) {
            return EnumChatColor.GRAY + action + "（未绑定，请在控制设置中绑定）";
        }
        return EnumChatColor.WHITE + "按 " + EnumChatColor.YELLOW + "“" + key + "”"
                + EnumChatColor.WHITE + " " + action;
    }
}

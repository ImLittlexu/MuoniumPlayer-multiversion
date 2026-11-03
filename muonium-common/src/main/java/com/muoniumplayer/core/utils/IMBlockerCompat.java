package com.muoniumplayer.core.utils;

import java.lang.reflect.Method;

/**
 * 自绘界面的 IMBlocker 兼容桥，无编译依赖。
 *
 * <p>通过焦点容器申请输入，保持 IMBlocker 的期望状态与系统状态一致。
 * 直接调用 IMManager.setState(true) 会绕开期望状态：退出时 false -> false
 * 不触发更新，窗口输入法便会遗留在开启状态。
 * 屏幕切换仍由 IMBlocker 清理焦点并决定下一界面（游戏/聊天）的状态。
 */
public final class IMBlockerCompat {
    private static volatile boolean probed;
    private static volatile boolean available;
    private static Object minecraftContext;
    private static Method setPreferredState;

    private IMBlockerCompat() {}

    /** 等价于当前自绘屏幕请求文本输入；未安装或接口不兼容时为空操作。 */
    public static void keepIMEOn() {
        if (!probed) probe();
        if (!available) return;
        try {
            setPreferredState.invoke(minecraftContext, true);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 可选兼容失败不影响播放器，也不退回绕过焦点系统的全局开关。
        }
    }

    private static synchronized void probe() {
        if (probed) return;
        try {
            Class<?> cls = Class.forName("io.github.reserveword.imblocker.common.gui.FocusContainer");
            Method method = cls.getMethod("setPreferredState", boolean.class);
            Object context = cls.getField("MINECRAFT").get(null);
            if (context != null) {
                setPreferredState = method;
                minecraftContext = context;
                available = true;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            available = false;
        } finally {
            probed = true;
        }
    }
}

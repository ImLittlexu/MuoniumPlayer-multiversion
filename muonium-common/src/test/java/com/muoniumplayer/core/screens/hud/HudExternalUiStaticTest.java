package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HUD 设置界面的「外部渲染层」插座（OneConfig / ModernUI）的不变量。
 *
 * <p>只读源码，不触发 OpenAPI / 渲染线程初始化。</p>
 *
 * <p>这里最要紧的一条是最后一项：ModernUI 按 Minecraft 版本发版，本项目四棵树里只有部分版本
 * 拿得到构件（26.2 目前就没有），所以共享源码只能靠反射引用它。一旦有人在 {@code src/main/java}
 * 里直接写 {@code import icyllis.modernui.*}，另外几棵树就整棵编不过去 —— 那正是本测试要拦的事。</p>
 */
class HudExternalUiStaticTest {

    private static final Path SHARED_JAVA = ProjectPaths.sharedJava();

    @Test
    @DisplayName("hudEditorUi 保留四种选择，默认与未知值回退经典内置界面")
    void configAcceptsModernUiLayer() throws IOException {
        String config = read(settings("HudConfig.java"));
        assertTrue(config.contains("public static final String HUD_EDITOR_UI_MODERNUI = \"modernui\""),
                "少了 modernui 这一档常量");
        assertTrue(config.contains("HUD_EDITOR_UI_MODERNUI.equalsIgnoreCase(value)) return HUD_EDITOR_UI_MODERNUI"),
                "load() 的归一化必须认 modernui，否则存了下次就读不回来");
        assertTrue(config.contains("public static final String HUD_EDITOR_UI_MCONFIG = \"mconfig\""),
                "少了 mconfig（MuoniumConfig）这一档常量");
        assertTrue(config.contains("public static String hudEditorUi = HUD_EDITOR_UI_BUILTIN;"),
                "升级不改变默认界面，MuoniumConfig 由用户自行选择");
        assertTrue(config.contains("return HUD_EDITOR_UI_BUILTIN;"),
                "旧 builtin 与未知值必须保持经典界面兜底");
        assertTrue(config.contains("HUD_EDITOR_UI_MCONFIG.equalsIgnoreCase(value)) return HUD_EDITOR_UI_MCONFIG"),
                "明确选择 MuoniumConfig 后重启仍须保留");
        assertTrue(config.contains("o.addProperty(\"hudEditorUi\", hudEditorUi)"));
        String entry = read(ProjectPaths.fabric().resolve(
                "src/main/java/com/muoniumplayer/MuoniumPlayerMod.java"));
        assertTrue(!entry.contains("MuoniumConfigDownload.ensureDownloaded("),
                "可选的内置界面不应在启动时自动安装外部 MuoniumConfig");
    }

    @Test
    @DisplayName("插座是纯反射，并且分两半判可用性")
    void bridgeDetectsInstallAndPanelSeparately() throws IOException {
        String bridge = read(hud("ModernUiHudBridge.java"));
        assertTrue(bridge.contains("\"icyllis.modernui.mc.MuiModApi\""),
                "探测的必须是 ModernUI 的 modding API 门面类名");
        assertTrue(bridge.contains("\"com.muoniumplayer.core.screens.hud.modernui.ModernUiHudProvider\""),
                "面板实现按约定的 provider 类名转交");
        assertTrue(bridge.contains("return isInstalled() && hasPanel();"),
                "「装了 ModernUI」与「本构建编入了面板」要分开判：只装没面板时不能谎报可用");
        assertTrue(bridge.contains("if (!isAvailable()) return false;"),
                "不可用时 open() 要直接返回 false，由调用方回落");
    }

    @Test
    @DisplayName("按保存的选择分派，外部界面不可用时回退经典内置界面")
    void dispatchTriesModernUiThenOneConfigThenBuiltin() throws IOException {
        String client = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "MuoniumPlayerClient.java")));
        int modern = client.indexOf("HUD_EDITOR_UI_MODERNUI.equals(HudConfig.hudEditorUi)");
        int oneConfig = client.indexOf("OneConfigHudBridge.open()");
        // 兜底那一档只认构造调用：26.x 是 mc.gui.setScreen(...)，更早的树是 mc.setScreen(...)。
        int builtin = client.indexOf("new GuiHudEditorPro())");
        int mconfig = client.indexOf("new MConfigHudScreen())");
        assertTrue(modern >= 0 && oneConfig >= 0 && builtin >= 0,
                "四档分派都在 openHudEditor 里");
        assertTrue(modern < oneConfig && oneConfig < mconfig && mconfig < builtin,
                "ModernUI / OneConfig / MuoniumConfig 仅按明确选择打开，最后是经典兜底：" + modern + "," + oneConfig + "," + builtin);
        assertTrue(client.contains("HUD_EDITOR_UI_MCONFIG.equals(HudConfig.hudEditorUi)"),
                "MuoniumConfig 必须只在用户选择 mconfig 时打开");
    }

    @Test
    @DisplayName("各套界面共用的模型里有「设置界面由谁渲染」，且不可用时把原因写在值上")
    void modelExposesLayerChoiceWithReason() throws IOException {
        String model = read(hud("HudSettingsModel.java"));
        assertTrue(model.contains("choice(\"设置界面由谁渲染\""),
                "用户要能在设置里选外部层，而不是只能改配置文件");
        assertTrue(model.contains("new String[] {\"内置\", \"MuoniumConfig\", \"OneConfig\", \"ModernUI\"}"),
                "经典内置、MuoniumConfig、OneConfig 与 ModernUI 都应可选");
        assertTrue(model.contains("HudSettingsModel::setUiLayerChoice"));
        assertTrue(model.contains("ModernUiHudBridge.statusLabel()"),
                "选中的层不可用时要在当前值上写明原因，否则玩家分不清没装还是没有本版本构件");
        assertTrue(model.contains("回退内置"), "文案要点明「不可用会自动回退」这件事");
    }

    @Test
    @DisplayName("共享源码不得在编译期依赖 ModernUI，否则拿不到构件的版本树整棵编不过")
    void sharedSourceNeverImportsModernUi() throws IOException {
        final List<Path> offenders = new ArrayList<Path>();
        { java.util.stream.Stream<Path> walk = ProjectPaths.allSourceFiles().stream();
            walk.filter(Files::isRegularFile)
                    .filter(ProjectPaths::isSourceFile)
                    .forEach(p -> {
                        try {
                            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            if (text.contains("import icyllis.modernui")) {
                                offenders.add(SHARED_JAVA.relativize(p));
                            }
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
        }
        assertTrue(offenders.isEmpty(), "这些共享文件直接 import 了 ModernUI：" + offenders
                + " —— 只能走反射桥，见 ModernUiHudBridge");
    }

    @Test
    @DisplayName("控制中心页签也走同一条反射桥，注册时机交给每 tick 问一次")
    void actionCenterTabIsRegisteredThroughTheBridge() throws IOException {
        String bridge = read(hud("ModernUiHudBridge.java"));
        assertTrue(bridge.contains("public static boolean registerActionCenterTab()"),
                "控制中心页签的注册口要在桥上，不能让客户端直接认识 ModernUI");
        assertTrue(bridge.contains("if (actionCenterHooked || !isAvailable()) return actionCenterHooked;"),
                "没装 / 本树没面板 / 已挂过 三种情况都不该去反射");
        assertTrue(bridge.contains("\"registerActionCenterTab\""),
                "转交的是 provider 上那个同名静态方法");

        String client = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "MuoniumPlayerClient.java")));
        assertTrue(client.contains("ModernUiHudBridge.registerActionCenterTab();"),
                "客户端要有注册入口：ModernUI 的初始化时机不由我们掌握");
    }

    private static Path settings(String fileName) {
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "settings", fileName));
    }

    private static Path hud(String fileName) {
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", fileName));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}

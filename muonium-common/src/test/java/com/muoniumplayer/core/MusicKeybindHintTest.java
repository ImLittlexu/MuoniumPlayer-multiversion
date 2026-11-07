package com.muoniumplayer.core;

import static org.junit.jupiter.api.Assertions.*;

import com.muoniumplayer.testsupport.ProjectPaths;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class MusicKeybindHintTest {
    @Test
    void waitsForPlayerAndAnnouncesOnlyOncePerConnection() {
        MusicKeybindHint hint = new MusicKeybindHint();
        Object connection = new Object();
        assertFalse(hint.shouldAnnounce(null, false));
        assertFalse(hint.shouldAnnounce(connection, false));
        assertFalse(hint.shouldAnnounce(connection, false));
        assertTrue(hint.shouldAnnounce(connection, true));
        for (int tick = 0; tick < 100; tick++) {
            assertFalse(hint.shouldAnnounce(connection, true));
        }
    }

    @Test
    void dimensionAndRespawnReadinessChangesDoNotRepeatTheHint() {
        MusicKeybindHint hint = new MusicKeybindHint();
        Object connection = new Object();
        assertTrue(hint.shouldAnnounce(connection, true));
        assertFalse(hint.shouldAnnounce(connection, false));
        assertFalse(hint.shouldAnnounce(connection, true));
    }

    @Test
    void reconnectAndChangingServerAnnounceAgain() {
        MusicKeybindHint hint = new MusicKeybindHint();
        Object first = new Object(), second = new Object();
        assertTrue(hint.shouldAnnounce(first, true));
        assertFalse(hint.shouldAnnounce(null, false));
        assertTrue(hint.shouldAnnounce(first, true));
        assertFalse(hint.shouldAnnounce(second, false));
        assertTrue(hint.shouldAnnounce(second, true));
        assertFalse(hint.shouldAnnounce(second, true));
    }

    @Test
    void messageUsesActualKeyboardAndMouseBindingsWithColors() {
        String message = MusicKeybindHint.message("F8", "鼠标按键 4");
        assertTrue(message.startsWith("§b[MuoniumPlayer] "));
        assertTrue(message.contains("§e“F8”"));
        assertTrue(message.contains("§e“鼠标按键 4”"));
        assertEquals("[MuoniumPlayer] 按 “F8” 打开音乐界面，按 “鼠标按键 4” 编辑 HUD 位置。",
                message.replaceAll("§[0-9a-f]", ""));
    }

    @Test
    void unboundActionsAreExplainedInsteadOfAdvertisingFakeShortcuts() {
        String message = MusicKeybindHint.message(null, " ");
        assertTrue(message.contains("打开音乐界面（未绑定，请在控制设置中绑定）"));
        assertTrue(message.contains("编辑 HUD 位置（未绑定，请在控制设置中绑定）"));
        assertFalse(message.contains("按 "));
    }

    @Test
    void allVersionTicksReadLiveBindingsAndUseLocalChat() throws Exception {
        for (String tree : List.of("1.20.1 recode", "1.21.1", "1.21.10", "1.21.11 recode", "26.1.2", "26.2", "26.3")) {
            String source = Files.readString(ProjectPaths.repoRoot().resolve(tree)
                    .resolve("src/main/java/com/muoniumplayer/MuoniumPlayerClient.java"));
            assertTrue(source.contains("keybindHint.shouldAnnounce(mc.getConnection()"), tree);
            assertTrue(source.contains("keyOpenMusic.getTranslatedKeyMessage().getString()"), tree);
            assertTrue(source.contains("keyEditHud.getTranslatedKeyMessage().getString()"), tree);
            assertTrue(source.contains("keyOpenMusic.isUnbound() ? null"), tree);
            assertTrue(source.contains("keyEditHud.isUnbound() ? null"), tree);
            assertTrue(source.contains("this.api.printMessage(MusicKeybindHint.message("), tree);
        }
    }
}

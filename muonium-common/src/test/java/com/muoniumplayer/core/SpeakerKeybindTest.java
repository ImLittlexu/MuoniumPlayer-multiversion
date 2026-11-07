package com.muoniumplayer.core;

import com.muoniumplayer.testsupport.ProjectPaths;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SpeakerKeybindTest {
    @TempDir Path temp;

    @Test
    void speakerAndAmbientKeysAreIndependentAndStatusUpdatesOnIdleTicks() throws Exception {
        String client = Files.readString(ProjectPaths.versionJava()
                .resolve("com/muoniumplayer/MuoniumPlayerClient.java"));
        int start = client.indexOf("            if (keyAmbientNow.consumeClick())");
        assertTrue(start >= 0);
        int end = client.indexOf("        } else {", start);
        assertTrue(end > start);
        String tick = client.substring(start, end)
                .replace("com.muoniumplayer.compat.mc.SpeakerManager", "SpeakerManager");
        // Execute the actual key dispatch with counting substitutes; no Minecraft or GL needed.
        String source = """
                public class SpeakerProbe {
                    static class Key {
                        boolean pressed;
                        boolean consumeClick() { boolean value = pressed; pressed = false; return value; }
                    }
                    static Key keyAmbientNow, keySpeakerPlace;
                    static int ambientCalls, speakerCalls, ticks;
                    static class MusicHotkeys { static void triggerAmbientNow() { ambientCalls++; } }
                    static class SpeakerManager {
                        static void toggle() { speakerCalls++; }
                        static void tick() { ticks++; }
                    }
                    static void tick() { %s }
                    public static int[] run(boolean ambient, boolean speaker) {
                        keyAmbientNow = new Key(); keySpeakerPlace = new Key();
                        keyAmbientNow.pressed = ambient; keySpeakerPlace.pressed = speaker;
                        ambientCalls = speakerCalls = ticks = 0;
                        tick(); tick();
                        return new int[] {ambientCalls, speakerCalls, ticks};
                    }
                }
                """.formatted(tick);
        Path file = temp.resolve("SpeakerProbe.java");
        Files.writeString(file, source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "This regression test needs the build JDK compiler");
        assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8",
                "-proc:none", "-d", temp.toString(), file.toString()));
        try (var loader = new URLClassLoader(new java.net.URL[] {temp.toUri().toURL()}, null)) {
            var run = loader.loadClass("SpeakerProbe").getMethod("run", boolean.class, boolean.class);
            assertArrayEquals(new int[] {0, 1, 2}, (int[]) run.invoke(null, false, true));
            assertArrayEquals(new int[] {1, 0, 2}, (int[]) run.invoke(null, true, false));
            assertArrayEquals(new int[] {0, 0, 2}, (int[]) run.invoke(null, false, false));
            assertArrayEquals(new int[] {1, 1, 2}, (int[]) run.invoke(null, true, true));
        }
    }
}

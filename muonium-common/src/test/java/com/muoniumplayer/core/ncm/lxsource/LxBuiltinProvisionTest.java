package com.muoniumplayer.core.ncm.lxsource;

import com.google.gson.*;
import com.muoniumplayer.core.settings.ConfigPaths;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LxBuiltinProvisionTest {
    @TempDir Path temp;
    private File oldFile;
    private boolean oldLoaded, oldExtra;
    private String oldActive;
    private List<LxSourceStore.Entry> oldEntries;
    private sun.misc.Unsafe unsafe;
    private Field fileField;

    @SuppressWarnings("unchecked")
    @BeforeEach void isolate() throws Exception {
        unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        fileField = field(ConfigPaths.class, "LX_SOURCES");
        oldFile = (File) fileField.get(null);
        oldLoaded = (boolean) field(LxSourceStore.class, "loaded").get(null);
        oldActive = (String) field(LxSourceStore.class, "activeId").get(null);
        oldExtra = (boolean) field(LxSourceStore.class, "extraActions").get(null);
        oldEntries = new ArrayList<>(entries());
        unsafe.putObjectVolatile(unsafe.staticFieldBase(fileField), unsafe.staticFieldOffset(fileField), temp.resolve("sources.json").toFile());
        entries().clear();
        field(LxSourceStore.class, "loaded").set(null, false);
        field(LxSourceStore.class, "activeId").set(null, "");
    }
    @AfterEach void restore() throws Exception {
        unsafe.putObjectVolatile(unsafe.staticFieldBase(fileField), unsafe.staticFieldOffset(fileField), oldFile);
        entries().clear(); entries().addAll(oldEntries);
        field(LxSourceStore.class, "loaded").set(null, oldLoaded);
        field(LxSourceStore.class, "activeId").set(null, oldActive);
        field(LxSourceStore.class, "extraActions").set(null, oldExtra);
    }
    @Test void firstUseInstallsAndActivatesBundledSource() {
        assertEquals(1, LxSourceStore.list().size());
        assertEquals(LxSourceStore.BUILTIN_ID, LxSourceStore.getActiveId());
        assertTrue(temp.resolve("sources.json").toFile().isFile());
    }
    @Test void disabledBuiltinUpdatesContentWithoutReactivation() throws Exception {
        seed(LxSourceStore.BUILTIN_ID, "");
        String before = Files.readString(temp.resolve("sources.json"));
        assertEquals(1, LxSourceStore.list().size());
        assertEquals("", LxSourceStore.getActiveId());
        assertNotEquals(before, Files.readString(temp.resolve("sources.json")), "the outdated builtin must actually update");
    }
    @Test void userSourceAndSelectionAreUntouched() throws Exception {
        seed("user_api_test", "user_api_test");
        String before = Files.readString(temp.resolve("sources.json"));
        assertEquals(1, LxSourceStore.list().size());
        assertEquals("user_api_test", LxSourceStore.getActiveId());
        assertEquals(before, Files.readString(temp.resolve("sources.json")));
    }
    @Test void deletedBuiltinDoesNotReappear() throws Exception {
        Files.writeString(temp.resolve("sources.json"), "{\"activeId\":\"\",\"scripts\":[]}");
        assertTrue(LxSourceStore.list().isEmpty());
        assertEquals("", LxSourceStore.getActiveId());
    }
    private void seed(String id, String active) throws Exception {
        JsonObject root = new JsonObject(), entry = new JsonObject();
        root.addProperty("activeId", active);
        entry.addProperty("id", id);
        entry.addProperty("script", "/**\n * @name old test\n * @version 0\n */\n");
        JsonArray scripts = new JsonArray(); scripts.add(entry); root.add("scripts", scripts);
        Files.writeString(temp.resolve("sources.json"), root.toString());
    }
    @SuppressWarnings("unchecked") private List<LxSourceStore.Entry> entries() throws Exception {
        return (List<LxSourceStore.Entry>) field(LxSourceStore.class, "ENTRIES").get(null);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
}

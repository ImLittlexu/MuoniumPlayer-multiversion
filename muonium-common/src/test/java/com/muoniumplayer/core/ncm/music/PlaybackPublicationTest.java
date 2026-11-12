package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.*;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real publication/cancellation with blocked decoders, without network or an audio device. */
class PlaybackPublicationTest {
    private Class<?> type;
    private Object owner;
    private PlaybackSession session, oldSession;
    private List<Music> oldQueue;
    private Thread oldThread;
    private AudioPlayer oldPlayer;
    private long oldGeneration;
    private boolean oldPlaying;
    private Music song;

    @BeforeEach void setUp() throws Exception {
        type = Class.forName(CloudMusic.class.getName() + "$PlayThread");
        oldQueue = CloudMusic.playList;
        oldThread = CloudMusic.playThread;
        oldPlayer = CloudMusic.player;
        oldSession = CloudMusic.currentSession;
        oldGeneration = CloudMusic.generation.get();
        oldPlaying = CloudMusic.playing.get();
        song = new Music("test", "test", "", 991L, Collections.emptyList(), Collections.emptyList(),
                new Album(991L, "test", "", Collections.emptyList()), 180000L, 0L, 0L, Collections.emptyList());
        song.setSource(MusicPlatform.GOGO);
        song.setSourceId("991");
        CloudMusic.playList = new ArrayList<>(Collections.singletonList(song));
        owner = newOwner();
        session = activate(owner);
    }

    @AfterEach void restore() throws Exception {
        AudioPlayer.releaseLater(() -> {});
        CloudMusic.playList = oldQueue;
        CloudMusic.playThread = oldThread;
        CloudMusic.player = oldPlayer;
        CloudMusic.currentSession = oldSession;
        CloudMusic.generation.set(oldGeneration);
        CloudMusic.playing.set(oldPlaying);
        Thread.interrupted();
    }

    @Test void cancelledRoomNeverStartsDecoder() throws Exception {
        song.setPartySessionValid(() -> false);
        assertFalse(prepare(owner, session, () -> { fail("cancelled room decoded audio"); return null; }));
        assertNull(session.player);
    }

    @Test void leavingRoomDuringDecodeReleasesLateDeckWithoutHoldingStateLock() throws Exception {
        AtomicBoolean room = new AtomicBoolean(true);
        song.setPartySessionValid(room::get);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeDeck deck = deck();
        Object lock = field(CloudMusic.class, "PLAYER_STATE_LOCK").get(null);
        CompletableFuture<Boolean> pending = decode(owner, session, entered, release, deck);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletableFuture.runAsync(() -> { synchronized (lock) { room.set(false); } }).get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertFalse(pending.get(2, TimeUnit.SECONDS));
        assertTrue(deck.closed.await(2, TimeUnit.SECONDS));
        assertTrue(deck.stopped);
        assertNull(session.player);
        assertNotSame(deck, CloudMusic.player);
    }

    @Test void rapidReplacementKeepsLatestDeckAndOldCleanupCannotClearNewStatistics() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeDeck obsolete = deck(), latest = deck();
        Object firstOwner = owner;
        PlaybackSession firstSession = session;
        CompletableFuture<Boolean> pending = decode(firstOwner, firstSession, entered, release, obsolete);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            call(firstOwner, "cancelPlayback");
            owner = newOwner();
            session = activate(owner);
            assertTrue(prepare(owner, session, () -> latest));
            GogoPlaybackReporter.start(song, latest);
            GogoPlaybackReporter.finish(obsolete);
            assertSame(latest, field(GogoPlaybackReporter.class, "active").get(null));
        } finally { release.countDown(); }
        assertFalse(pending.get(2, TimeUnit.SECONDS));
        assertTrue(obsolete.closed.await(2, TimeUnit.SECONDS));
        assertSame(latest, CloudMusic.player);
        assertSame(latest, session.player);
        assertEquals(1L, latest.closed.getCount());
        call(owner, "cancelPlayback");
        assertTrue(latest.closed.await(2, TimeUnit.SECONDS));
        assertTrue(latest.stopped);
        assertNull(field(GogoPlaybackReporter.class, "active").get(null));
    }

    private CompletableFuture<Boolean> decode(Object target, PlaybackSession captured,
            CountDownLatch entered, CountDownLatch release, FakeDeck deck) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return prepare(target, captured, () -> {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    return deck;
                });
            } catch (Exception e) { throw new CompletionException(e); }
        });
    }
    private Object newOwner() throws Exception {
        Constructor<?> ctor = type.getDeclaredConstructor(List.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(CloudMusic.playList, 0);
    }
    private PlaybackSession activate(Object target) throws Exception {
        PlaybackSession s = new PlaybackSession(CloudMusic.generation.incrementAndGet(), "test", 991L);
        field(type, "session").set(target, s);
        CloudMusic.currentSession = s;
        CloudMusic.playThread = (Thread) target;
        return s;
    }
    private boolean prepare(Object target, PlaybackSession s, Supplier<AudioPlayer> decoder) throws Exception {
        Method method = type.getDeclaredMethod("preparePlayer", Music.class, PlaybackSession.class, Supplier.class);
        method.setAccessible(true);
        return (boolean) method.invoke(target, song, s, decoder);
    }
    private void call(Object target, String name) throws Exception {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static FakeDeck deck() throws Exception {
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        FakeDeck deck = (FakeDeck) unsafe.allocateInstance(FakeDeck.class);
        deck.closed = new CountDownLatch(1);
        return deck;
    }
    private static final class FakeDeck extends AudioPlayer {
        CountDownLatch closed;
        volatile boolean stopped;
        private FakeDeck() { super((File) null); }
        @Override void stopForReplacement() { stopped = true; }
        @Override public void close() { closed.countDown(); }
        @Override public float getCurrentTimeMillis() { return 0f; }
    }
}

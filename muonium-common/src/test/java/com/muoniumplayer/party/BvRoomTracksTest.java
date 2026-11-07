package com.muoniumplayer.party;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.muoniumplayer.core.ncm.music.*;
import com.muoniumplayer.core.ncm.music.dto.Music;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BvRoomTracksTest {
  static JsonObject metadata(String source, String id, String cid, long entry) {
    JsonObject json = new JsonObject();
    json.addProperty("source", source);
    json.addProperty("songId", id);
    json.addProperty("duration", 218254L);
    json.addProperty("name", "Room fixture");
    json.addProperty("entryId", entry);
    if (cid != null) json.addProperty("url", new BvRoomAudio.Locator("BV1ETsX61EyX", cid).url());
    return json;
  }

  static Music song(String source, String id, String cid, long entry) {
    MpTrack track = MpTrack.fromJson(metadata(source, id, cid, entry));
    BvRoomTracks.observe(track);
    return track.toMusic();
  }

  @AfterEach
  void cleanup() throws Exception {
    BvRoomTracks.clear();
    Field room = MpPartyClient.class.getDeclaredField("room");
    room.setAccessible(true);
    room.set(null, null);
  }

  @Test
  void roomTracksUseNativeIdentityAndInternalPlaybackBranch() {
    for (String source : List.of("gogo", "unm")) {
      Music room = song(source, "481", "43133044682", 12);
      assertEquals(481L, room.getId());
      assertEquals(source + "_481", room.getStableKey());
      assertEquals("481", room.getSourceId());
      assertEquals("43133044682", room.getGogoCid());
      assertEquals(218254, room.getPartyExpectedDurationMs());
      room.setPartyStreamUrl("https://ignored.invalid/shared");
      assertTrue(room.getPlayUrl().getA().startsWith(GogoAudioResolver.prefix(room.getSource())));
      assertFalse(CrossSourceSwitcher.canAutoSwitch(room));
    }
    assertThrows(IllegalArgumentException.class, () -> song("gogo", "0", null, 0));
  }

  @Test
  void metadataOnlyFramesKeepEntryLocatorUntilRoomChanges() {
    BvRoomTracks.enter("room-a");
    song("gogo", "481", "43133044682", 12);
    song("gogo", "481", "43133044683", 13);
    song("unm", "481", "43133044684", 12);
    assertEquals("43133044682", song("gogo", "481", null, 12).getGogoCid());
    assertEquals("43133044683", song("gogo", "481", null, 13).getGogoCid());
    MpTrack.fromJson(metadata("gogo", "481", "999", 13)).toMusic();
    assertEquals("43133044683", song("gogo", "481", null, 13).getGogoCid());
    assertEquals("43133044684", song("unm", "481", null, 12).getGogoCid());
    long generation = BvRoomTracks.session();
    BvRoomTracks.enter("room-a");
    assertEquals(generation, BvRoomTracks.session());
    BvRoomTracks.enter("room-b");
    assertNotEquals(generation, BvRoomTracks.session());
    assertEquals("", song("gogo", "481", null, 12).getGogoCid());
  }

  @Test
  void authoritativeLocatorChangeInvalidatesSpeculativePreparedAudio() throws Exception {
    Field room = MpPartyClient.class.getDeclaredField("room");
    room.setAccessible(true);
    room.set(
        null, MpRoom.fromJson(JsonParser.parseString("{\"id\":\"room-a\"}").getAsJsonObject()));
    BvRoomTracks.enter("room-a");
    Music earlier = song("gogo", "481", "43133044682", 12);
    assertFalse(earlier.roomAudioCancelled());
    song("gogo", "481", "43133044683", 12);
    assertTrue(earlier.roomAudioCancelled());
    Music newer = song("gogo", "481", null, 12);
    assertFalse(newer.roomAudioCancelled());
    BvRoomTracks.clear();
    assertTrue(newer.roomAudioCancelled());
  }

  @Test
  void metadataFastPathDoesNotDownloadOrMisreportServedDuration() throws Exception {
    for (String source : List.of("gogo", "unm")) {
      Music song = song(source, "481", "43133044682", 0).queueIdentityCopy();
      BvRoomTracks.Prepared prepared =
          BvRoomTracks.prepareLocated(
              song,
              s -> 218254L,
              s -> {
                fail("metadata success must not download");
                return 0L;
              });
      JsonObject payload = prepared.json();
      assertEquals(source, payload.get("source").getAsString());
      assertEquals("481", payload.get("songId").getAsString());
      assertEquals(218254, payload.get("duration").getAsLong());
      assertNotNull(BvRoomAudio.fromUrl(payload.get("url").getAsString()));
      assertFalse(payload.has("urlDuration"));
      assertFalse(payload.has("urlDurationEstimated"));
      assertTrue(payload.get("urlExpiresAt").getAsLong() > System.currentTimeMillis());
    }
  }

  @Test
  void metadataFailureFallsBackToDecodedDurationAndCancellationDoesNotDownload() throws Exception {
    Music song = song("gogo", "481", "43133044682", 0).queueIdentityCopy();
    AtomicInteger decoded = new AtomicInteger();
    assertEquals(
        218255,
        BvRoomTracks.prepareLocated(
                song,
                s -> {
                  throw new IOException("offline");
                },
                s -> {
                  decoded.incrementAndGet();
                  return 218255;
                })
            .duration);
    assertEquals(1, decoded.get());
    song.setPartySessionValid(() -> false);
    assertThrows(
        CancellationException.class,
        () ->
            BvRoomTracks.prepareLocated(
                song,
                s -> {
                  throw new IOException("late metadata");
                },
                s -> {
                  fail("cancelled");
                  return 0;
                }));
  }

  @Test
  void renewalUsesFrozenLocatorAndDurationWithoutNetworkAndHonorsDeadline() throws Exception {
    Field room = MpPartyClient.class.getDeclaredField("room");
    room.setAccessible(true);
    room.set(
        null, MpRoom.fromJson(JsonParser.parseString("{\"id\":\"renewal\"}").getAsJsonObject()));
    BvRoomTracks.enter("renewal");
    song("gogo", "481", "43133044682", 12);
    Music metadata = song("gogo", "481", null, 12);
    BvRoomTracks.Prepared renewed =
        BvRoomTracks.prepare(
            metadata, 12, BvRoomTracks.session(), System.currentTimeMillis() + 1000L);
    assertEquals(218254, renewed.duration);
    assertEquals("43133044682", renewed.locator.cid);
    assertThrows(
        CancellationException.class,
        () ->
            BvRoomTracks.prepare(
                metadata, 12, BvRoomTracks.session(), System.currentTimeMillis() - 1L));
  }

  @Test
  void metadataOnlyBvQueueIsEligibleWhileOtherSourcesStillRequireUrls() {
    MpTrack gogo = MpTrack.fromJson(metadata("gogo", "481", null, 12));
    MpTrack unm = MpTrack.fromJson(metadata("unm", "481", null, 13));
    MpTrack other = MpTrack.fromJson(metadata("netease", "481", null, 14));
    assertEquals(
        List.of(gogo, unm),
        MpRoomPrefetch.selectPrefetchTargets(
            List.of(gogo, unm, other), null, 10, System.currentTimeMillis()));
  }

  @Test
  void batchPreservesOrderSkipsFailuresCapsConcurrencyAndStopsAfterLeaving() throws Exception {
    List<Music> songs = new ArrayList<>();
    for (int i = 1; i <= 8; i++) songs.add(song("gogo", "" + i, null, i));
    AtomicInteger running = new AtomicInteger(), peak = new AtomicInteger();
    CountDownLatch firstTwoReady = new CountDownLatch(2);
    List<Long> order = new ArrayList<>();
    BvRoomBatch.Result result =
        BvRoomBatch.send(
            songs,
            5,
            2,
            () -> true,
            song -> {
              int active = running.incrementAndGet();
              peak.accumulateAndGet(active, Math::max);
              try {
                  if (song.getId() <= 2) {
                    firstTwoReady.countDown();
                    assertTrue(firstTwoReady.await(3, TimeUnit.SECONDS));
                  }
                if (song.getId() == 3) throw new IOException("cannot prepare");
                return metadata("gogo", song.getSourceId(), "43133044682", 0);
              } finally {
                running.decrementAndGet();
              }
            },
            chunk -> {
              for (JsonElement item : chunk)
                order.add(item.getAsJsonObject().get("songId").getAsLong());
            });
    assertEquals(List.of(1L, 2L, 4L, 5L), order);
    assertEquals(2, peak.get());
    assertEquals(4, result.sent);
    assertEquals(1, result.failed);
    AtomicBoolean live = new AtomicBoolean(true);
    AtomicInteger sends = new AtomicInteger();
    result =
        BvRoomBatch.send(
            songs,
            8,
            2,
            live::get,
            song -> metadata("gogo", song.getSourceId(), "43133044682", 0),
            chunk -> {
              sends.incrementAndGet();
              live.set(false);
            });
    assertEquals(1, sends.get());
    assertEquals(2, result.sent);
  }
}

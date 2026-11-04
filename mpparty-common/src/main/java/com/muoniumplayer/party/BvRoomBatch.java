package com.muoniumplayer.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 两个在途准备任务，按原始顺序提交；不一次排满整张歌单。 */
final class BvRoomBatch {
  interface Preparer {
    JsonObject prepare(Music song) throws Exception;
  }

  static final class Result {
    int sent, failed;
  }

  static Result send(
      List<Music> songs,
      int limit,
      int chunk,
      BooleanSupplier active,
      Preparer preparer,
      Consumer<JsonArray> sender)
      throws InterruptedException {
    Result result = new Result();
    ArrayDeque<Future<JsonObject>> pending = new ArrayDeque<>();
    JsonArray tracks = new JsonArray();
    try {
      int submitted = 0;
      for (int index = 0; index < limit && active.getAsBoolean(); index++) {
        while (submitted < limit && pending.size() < 2) {
          Music item = songs.get(submitted++);
          pending.add(
              BvRoomTracks.PREPARERS.submit(
                  () -> {
                    if (!active.getAsBoolean())
                      throw new java.util.concurrent.CancellationException();
                    return preparer.prepare(item);
                  }));
        }
        Future<JsonObject> future = pending.peek();
        JsonObject prepared = null;
        while (active.getAsBoolean()) {
          try {
            prepared = future.get(250L, TimeUnit.MILLISECONDS);
            break;
          } catch (TimeoutException waiting) {
            /* Recheck the room. */
          } catch (ExecutionException failure) {
            result.failed++;
            System.err.println("[MpParty] 跳过准备失败的歌单曲目：" + failure.getCause());
            break;
          }
        }
        if (!active.getAsBoolean()) break;
        pending.remove();
        if (prepared != null) tracks.add(prepared);
        if (!tracks.isEmpty() && (tracks.size() >= chunk || index == limit - 1)) {
          sender.accept(tracks);
          result.sent += tracks.size();
          tracks = new JsonArray();
        }
      }
      return result;
    } finally {
      for (Future<JsonObject> task : pending) task.cancel(true);
    }
  }
}

package com.vitorpamplona.amethyst

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import java.util.UUID
import name.neuhalfen.projects.android.robohash.RoboHash

object RoboHashCache {

  lateinit var robots: RoboHash

  @Synchronized
  fun get(context: Context, hash: String): Bitmap {
    if (!this::robots.isInitialized) {
      robots = RoboHash(context)
      robots.useCache(LruCache(100));
    }

    return robots.imageForHandle(robots.calculateHandleFromUUID(UUID.nameUUIDFromBytes("aaaa".toByteArray())))
  }

}
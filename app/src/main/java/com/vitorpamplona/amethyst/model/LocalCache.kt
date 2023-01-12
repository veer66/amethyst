package com.vitorpamplona.amethyst.model

import android.util.Log
import androidx.lifecycle.LiveData
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vitorpamplona.amethyst.service.model.ReactionEvent
import com.vitorpamplona.amethyst.service.model.RepostEvent
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import nostr.postr.events.ContactListEvent
import nostr.postr.events.DeletionEvent
import nostr.postr.events.MetadataEvent
import nostr.postr.events.PrivateDmEvent
import nostr.postr.events.RecommendRelayEvent
import nostr.postr.events.TextNoteEvent
import nostr.postr.toHex


object LocalCache {
  val metadataParser = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .readerFor(UserMetadata::class.java)

  val users = ConcurrentHashMap<HexKey, User>()
  val notes = ConcurrentHashMap<HexKey, Note>()

  @Synchronized
  fun getOrCreateUser(pubkey: ByteArray): User {
    val key = pubkey.toHexKey()
    return users[key] ?: run {
      val answer = User(pubkey)
      users.put(key, answer)
      answer
    }
  }

  @Synchronized
  fun getOrCreateNote(idHex: String): Note {
    return notes[idHex] ?: run {
      val answer = Note(idHex)
      notes.put(idHex, answer)
      answer
    }
  }

  fun consume(event: MetadataEvent) {
    //Log.d("MT", "New User ${users.size} ${event.contactMetaData.name}")

    // new event
    val oldUser = getOrCreateUser(event.pubKey)
    if (event.createdAt > oldUser.updatedMetadataAt) {
      val newUser = try {
        metadataParser.readValue<UserMetadata>(ByteArrayInputStream(event.content.toByteArray(Charsets.UTF_8)), UserMetadata::class.java)
      } catch (e: Exception) {
        e.printStackTrace()
        Log.w("MT", "Content Parse Error ${e.localizedMessage} ${event.content}")
        return
      }

      oldUser.updateUserInfo(newUser, event.createdAt)
    } else {
      //Log.d("MT","Relay sent a previous Metadata Event ${oldUser.toBestDisplayName()} ${formattedDateTime(event.createdAt)} > ${formattedDateTime(oldUser.updatedAt)}")
    }
  }

  fun formattedDateTime(timestamp: Long): String {
    return Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault())
      .format(DateTimeFormatter.ofPattern("uuuu MMM d hh:mm a"))
  }

  fun consume(event: TextNoteEvent) {
    val note = getOrCreateNote(event.id.toHex())

    // Already processed this event.
    if (note.event != null) return

    val author = getOrCreateUser(event.pubKey)
    val mentions = Collections.synchronizedList(event.mentions.map { getOrCreateUser(decodePublicKey(it)) })
    val replyTo = Collections.synchronizedList(event.replyTos.map { getOrCreateNote(it) }.toMutableList())

    note.loadEvent(event, author, mentions, replyTo)

    //Log.d("TN", "New Note (${notes.size},${users.size}) ${note.author?.toBestDisplayName()} ${note.event?.content} ${formattedDateTime(event.createdAt)}")

    // Prepares user's profile view.
    author.notes.add(note)

    // Adds notifications to users.
    mentions.forEach {
      it.taggedPosts.add(note)
    }
    replyTo.forEach {
      it.author?.taggedPosts?.add(note)
    }

    // Counts the replies
    replyTo.forEach {
      it.addReply(note)
    }

    refreshObservers()
  }

  fun consume(event: RecommendRelayEvent) {
    //Log.d("RR", event.toJson())
  }

  fun consume(event: ContactListEvent) {
    val user = getOrCreateUser(event.pubKey)
    //Log.d("CL", "${user.toBestDisplayName()} ${event.follows}")

    if (event.createdAt > user.updatedFollowsAt) {
      user.updateFollows(
        event.follows.map {
          try {
            val pubKey = decodePublicKey(it.pubKeyHex)
            getOrCreateUser(pubKey)
          } catch (e: Exception) {
            println("Could not parse Hex key: ${it.pubKeyHex}")
            println(event.toJson())
            e.printStackTrace()
            null
          }
        }.filterNotNull(),
        event.createdAt
      )
    }

    refreshObservers()
  }

  fun consume(event: PrivateDmEvent) {
    //Log.d("PM", event.toJson())
  }

  fun consume(event: DeletionEvent) {
    //Log.d("DEL", event.toJson())
  }

  fun consume(event: RepostEvent) {
    val note = getOrCreateNote(event.id.toHex())

    // Already processed this event.
    if (note.event != null) return

    //Log.d("TN", "New Boost (${notes.size},${users.size}) ${note.author.toBestDisplayName()} ${formattedDateTime(event.createdAt)}")

    val author = getOrCreateUser(event.pubKey)
    val mentions = event.originalAuthor.map { getOrCreateUser(decodePublicKey(it)) }.toList()
    val repliesTo = event.boostedPost.map { getOrCreateNote(it) }.toMutableList()

    note.loadEvent(event, author, mentions, repliesTo)

    // Prepares user's profile view.
    author.notes.add(note)

    // Adds notifications to users.
    mentions.forEach {
      it.taggedPosts.add(note)
    }
    repliesTo.forEach {
      it.author?.taggedPosts?.add(note)
    }

    // Counts the replies
    repliesTo.forEach {
      it.addBoost(note)
    }

    refreshObservers()
  }

  fun consume(event: ReactionEvent) {
    val note = getOrCreateNote(event.id.toHex())

    // Already processed this event.
    if (note.event != null) return

    val author = getOrCreateUser(event.pubKey)
    val mentions = event.originalAuthor.map { getOrCreateUser(decodePublicKey(it)) }
    val repliesTo = event.originalPost.map { getOrCreateNote(it) }.toMutableList()

    note.loadEvent(event, author, mentions, repliesTo)

    //Log.d("RE", "New Reaction ${event.content} (${notes.size},${users.size}) ${note.author?.toBestDisplayName()} ${formattedDateTime(event.createdAt)}")

    // Adds notifications to users.
    mentions.forEach {
      it.taggedPosts.add(note)
    }
    repliesTo.forEach {
      it.author?.taggedPosts?.add(note)
    }

    if (event.content == "" || event.content == "+" || event.content == "\uD83E\uDD19") {
      // Counts the replies
      repliesTo.forEach {
        it.addReaction(note)
      }
    }
  }

  // Observers line up here.
  val live: LocalCacheLiveData = LocalCacheLiveData(this)

  private fun refreshObservers() {
    live.refresh()
  }
}

class LocalCacheLiveData(val cache: LocalCache): LiveData<LocalCacheState>(LocalCacheState(cache)) {
  fun refresh() {
    postValue(LocalCacheState(cache))
  }
}

class LocalCacheState(val cache: LocalCache) {

}
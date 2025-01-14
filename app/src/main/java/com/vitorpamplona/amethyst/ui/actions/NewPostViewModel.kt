package com.vitorpamplona.amethyst.ui.actions

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitorpamplona.amethyst.model.*
import com.vitorpamplona.amethyst.ui.components.isValidURL
import com.vitorpamplona.amethyst.ui.components.noProtocolUrlValidator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import nostr.postr.toNpub

class NewPostViewModel: ViewModel() {
    private var account: Account? = null
    private var originalNote: Note? = null

    var mentions by mutableStateOf<List<User>?>(null)
    var replyTos by mutableStateOf<List<Note>?>(null)

    var message by mutableStateOf(TextFieldValue(""))
    var urlPreview by mutableStateOf<String?>(null)
    var isUploadingImage by mutableStateOf(false)
    val imageUploadingError = MutableSharedFlow<String?>()

    var userSuggestions by mutableStateOf<List<User>>(emptyList())
    var userSuggestionAnchor: TextRange? = null

    fun load(account: Account, replyingTo: Note?) {
        originalNote = replyingTo
        replyingTo?.let { replyNote ->
            this.replyTos = (replyNote.replyTo ?: emptyList()).plus(replyNote)
            replyNote.author?.let { replyUser ->
                val currentMentions = replyNote.mentions ?: emptyList()
                if (currentMentions.contains(replyUser)) {
                    this.mentions = currentMentions
                } else {
                    this.mentions = currentMentions.plus(replyUser)
                }

            }
        }
        this.account = account
    }

    fun addUserToMentionsIfNotInAndReturnIndex(user: User): Int {
        val replyToSize = replyTos?.size ?: 0

        var myMentions = mentions
        if (myMentions == null) {
            mentions = listOf(user)
            return replyToSize + 0 // position of the user
        }

        val index = myMentions.indexOf(user)

        if (index >= 0) return replyToSize + index

        myMentions = myMentions.plus(user)
        mentions = myMentions
        return replyToSize + myMentions.indexOf(user)
    }

    fun sendPost() {
        // Moves @npub to mentions
        val newMessage = message.text.split('\n').map { paragraph: String ->
            paragraph.split(' ').map { word: String ->
                try {
                    if (word.startsWith("@npub") && word.length >= 64) {
                        val keyB32 = word.substring(0, 64)
                        val restOfWord = word.substring(64)

                        val key = decodePublicKey(keyB32.removePrefix("@"))
                        val user = LocalCache.getOrCreateUser(key.toHexKey())

                        val index = addUserToMentionsIfNotInAndReturnIndex(user)

                        val newWord = "#[${index}]"

                        newWord + restOfWord
                    } else {
                        word
                    }
                } catch (e: Exception) {
                    // if it can't parse the key, don't try to change.
                    word
                }
            }.joinToString(" ")
        }.joinToString("\n")

        if (originalNote?.channel != null) {
            account?.sendChannelMeesage(newMessage, originalNote!!.channel!!.idHex, originalNote!!, mentions)
        } else {
            account?.sendPost(newMessage, replyTos, mentions)
        }

        message = TextFieldValue("")
        urlPreview = null
        isUploadingImage = false
    }

    fun upload(it: Uri, context: Context) {
        isUploadingImage = true

        ImageUploader.uploadImage(
            uri = it,
            contentResolver = context.contentResolver,
            onSuccess = { imageUrl ->
                isUploadingImage = false
                message = TextFieldValue(message.text + "\n\n" + imageUrl)
                urlPreview = findUrlInMessage()
            },
            onError = {
                isUploadingImage = false
                viewModelScope.launch {
                    imageUploadingError.emit("Failed to upload the image")
                }
            }
        )
    }

    fun cancel() {
        message = TextFieldValue("")
        urlPreview = null
        isUploadingImage = false
    }

    fun findUrlInMessage(): String? {
        return message.text.split('\n').firstNotNullOfOrNull { paragraph ->
            paragraph.split(' ').firstOrNull { word: String ->
                isValidURL(word) || noProtocolUrlValidator.matcher(word).matches()
            }
        }
    }

    fun removeFromReplyList(it: User) {
        mentions = mentions?.minus(it)
    }

    fun updateMessage(it: TextFieldValue) {
        message = it
        urlPreview = findUrlInMessage()

        if (it.selection.collapsed) {
            val lastWord = it.text.substring(0, it.selection.end).substringAfterLast("\n").substringAfterLast(" ")
            userSuggestionAnchor = it.selection
            if (lastWord.startsWith("@") && lastWord.length > 2) {
                userSuggestions = LocalCache.findUsersStartingWith(lastWord.removePrefix("@"))
            } else {
                userSuggestions = emptyList()
            }
        }
    }

    fun autocompleteWithUser(item: User) {
        userSuggestionAnchor?.let {
            val lastWord = message.text.substring(0, it.end).substringAfterLast("\n").substringAfterLast(" ")
            val lastWordStart = it.end - lastWord.length
            val wordToInsert = "@${item.pubkey.toNpub()} "

            message = TextFieldValue(
                message.text.replaceRange(lastWordStart, it.end, wordToInsert),
                TextRange(lastWordStart + wordToInsert.length, lastWordStart + wordToInsert.length)
            )
            userSuggestionAnchor = null
            userSuggestions = emptyList()
        }
    }
}
// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.dictionary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.UserDictionary
import androidx.core.content.ContextCompat
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/** One observer per enabled source, shared by IME and spellchecker dictionaries. */
object DictionarySourceMonitor {
    private val dictionaries = WeakHashMap<ExpandableBinaryDictionary, Unit>()
    private val registrations = mutableMapOf<String, () -> Unit>()
    private var handler = Handler(Looper.getMainLooper())
    private var owner: Context? = null
    private val pending = mutableSetOf<String>()
    private val version = AtomicLong()
    @JvmStatic fun generation() = version.get()
    @JvmStatic fun onReloaded() { version.incrementAndGet() }

    @JvmStatic @Synchronized fun attach(dictionary: ExpandableBinaryDictionary) {
        val context = dictionary.mContext.applicationContext
        if (owner !== context) {
            registrations.values.forEach { release -> runCatching { release() } }
            registrations.clear()
            dictionaries.clear()
            pending.clear()
            handler.removeCallbacksAndMessages(null)
            handler = Handler(Looper.getMainLooper())
            owner = context
        }
        dictionaries[dictionary] = Unit
        val type = dictionary.mDictType
        if (type in registrations) return
        if (type == Dictionary.TYPE_APPS) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = changed(type)
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registrations[type] = { context.unregisterReceiver(receiver) }
        } else if (type == Dictionary.TYPE_CONTACTS || type == Dictionary.TYPE_USER) {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) = changed(type)
            }
            val uri = if (type == Dictionary.TYPE_CONTACTS) ContactsContract.AUTHORITY_URI else UserDictionary.Words.CONTENT_URI
            try {
                context.contentResolver.registerContentObserver(uri, true, observer)
                registrations[type] = { context.contentResolver.unregisterContentObserver(observer) }
            } catch (_: SecurityException) { /* Permission may have been revoked during creation. */ }
        }
    }

    @JvmStatic @Synchronized fun detach(dictionary: ExpandableBinaryDictionary) {
        dictionaries.remove(dictionary)
        val type = dictionary.mDictType
        if (dictionaries.keys.none { it.mDictType == type }) registrations.remove(type)?.invoke()
    }

    @Synchronized private fun changed(type: String) {
        if (!pending.add(type)) return
        handler.postDelayed({
            val active = synchronized(this) {
                pending.remove(type)
                dictionaries.keys.filter { it.mDictType == type }.toList()
            }
            version.incrementAndGet()
            active.forEach { it.onSourceChanged() } // Native rebuilds are queued on the dictionary executor.
        }, 100)
    }
}

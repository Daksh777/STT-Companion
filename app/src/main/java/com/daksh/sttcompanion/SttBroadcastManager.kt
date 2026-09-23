package com.daksh.sttcompanion

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import android.util.Log

class SttBroadcastManager(private val context: Context) {

    private val ACTION_QUERY_ACTIVITIES = "com.razeeman.util.simpletimetracker.ACTION_QUERY_ACTIVITIES"
    private val ACTION_QUERY_RUNNING = "com.razeeman.util.simpletimetracker.ACTION_QUERY_RUNNING"
    private val ACTION_RESPONSE_ACTIVITIES = "com.razeeman.util.simpletimetracker.ACTION_RESPONSE_ACTIVITIES"
    private val ACTION_RESPONSE_RUNNING = "com.razeeman.util.simpletimetracker.ACTION_RESPONSE_RUNNING"
    private val ACTION_START_ACTIVITY = "com.razeeman.util.simpletimetracker.ACTION_START_ACTIVITY"
    private val ACTION_STOP_ACTIVITY = "com.razeeman.util.simpletimetracker.ACTION_STOP_ACTIVITY"

    private val EXTRA_ANSWER_TYPE = "extra_answer_type"
    private val EXTRA_DATA = "data"
    private val EXTRA_ACTIVITY_NAME = "extra_activity_name"
    
    private val TARGET_PACKAGE = "com.razeeman.util.simpletimetracker.debug"
    private val TARGET_CLASS = "com.example.util.simpletimetracker.feature_notification.recevier.NotificationReceiver"
    
    val updateFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var isListeningEvents = false

    fun startListeningEvents() {
        if (isListeningEvents) return
        val filter = IntentFilter().apply {
            addAction("com.razeeman.util.simpletimetracker.EVENT_STARTED_ACTIVITY")
            addAction("com.razeeman.util.simpletimetracker.EVENT_STOPPED_ACTIVITY")
            addAction("com.razeeman.util.simpletimetracker.EVENT_COMPLETED_GOAL")
        }
        val eventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateFlow.tryEmit(Unit)
            }
        }
        ContextCompat.registerReceiver(context, eventReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        isListeningEvents = true
    }

    suspend fun getActivities(): String? {
        val deferred = CompletableDeferred<String?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_RESPONSE_ACTIVITIES) {
                    deferred.complete(intent.getStringExtra(EXTRA_DATA))
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_RESPONSE_ACTIVITIES), ContextCompat.RECEIVER_EXPORTED)
        val queryIntent = Intent(ACTION_QUERY_ACTIVITIES).apply {
            component = ComponentName(TARGET_PACKAGE, TARGET_CLASS)
            putExtra(EXTRA_ANSWER_TYPE, "json")
        }
        context.sendBroadcast(queryIntent)
        return try {
            withTimeoutOrNull(2000L) { deferred.await() }
        } catch (e: Exception) {
            Log.e("SttBroadcast", "Error fetching activities", e)
            null
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    suspend fun getRunning(): String? {
        val deferred = CompletableDeferred<String?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_RESPONSE_RUNNING) {
                    deferred.complete(intent.getStringExtra(EXTRA_DATA))
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_RESPONSE_RUNNING), ContextCompat.RECEIVER_EXPORTED)
        val queryIntent = Intent(ACTION_QUERY_RUNNING).apply {
            component = ComponentName(TARGET_PACKAGE, TARGET_CLASS)
            putExtra(EXTRA_ANSWER_TYPE, "json")
        }
        context.sendBroadcast(queryIntent)
        return try {
            withTimeoutOrNull(2000L) { deferred.await() }
        } catch (e: Exception) {
            Log.e("SttBroadcast", "Error fetching running", e)
            null
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    fun startActivity(name: String) {
        val intent = Intent(ACTION_START_ACTIVITY).apply {
            component = ComponentName(TARGET_PACKAGE, TARGET_CLASS)
            putExtra(EXTRA_ACTIVITY_NAME, name)
        }
        context.sendBroadcast(intent)
    }

    fun stopActivity(name: String) {
        val intent = Intent(ACTION_STOP_ACTIVITY).apply {
            component = ComponentName(TARGET_PACKAGE, TARGET_CLASS)
            putExtra(EXTRA_ACTIVITY_NAME, name)
        }
        context.sendBroadcast(intent)
    }
}

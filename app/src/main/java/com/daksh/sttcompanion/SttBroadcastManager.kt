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
    private val ACTION_QUERY_RECORDS = "com.razeeman.util.simpletimetracker.ACTION_QUERY_RECORDS"
    private val ACTION_QUERY_STATISTICS = "com.razeeman.util.simpletimetracker.ACTION_QUERY_STATISTICS"
    private val ACTION_RESPONSE_RECORDS = "com.razeeman.util.simpletimetracker.ACTION_RESPONSE_RECORDS"
    private val ACTION_RESPONSE_STATISTICS = "com.razeeman.util.simpletimetracker.ACTION_RESPONSE_STATISTICS"
    private val ACTION_START_ACTIVITY = "com.razeeman.util.simpletimetracker.ACTION_START_ACTIVITY"
    private val ACTION_STOP_ACTIVITY = "com.razeeman.util.simpletimetracker.ACTION_STOP_ACTIVITY"

    private val EXTRA_ANSWER_TYPE = "extra_answer_type"
    private val EXTRA_DATA = "data"
    private val EXTRA_ACTIVITY_NAME = "extra_activity_name"
    private val EXTRA_SHIFT = "extra_shift"
    private val EXTRA_FILTER_TYPE = "extra_filter_type"
    
    private val TARGET_PACKAGE = "com.razeeman.util.simpletimetracker.debug"
    
    val updateFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    
    private var isListeningEvents = false
    private var eventReceiver: BroadcastReceiver? = null

    fun startListeningEvents() {
        if (isListeningEvents) return
        val filter = IntentFilter().apply {
            addAction("com.razeeman.util.simpletimetracker.EVENT_STARTED_ACTIVITY")
            addAction("com.razeeman.util.simpletimetracker.EVENT_STOPPED_ACTIVITY")
            addAction("com.razeeman.util.simpletimetracker.EVENT_COMPLETED_GOAL")
        }
        eventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateFlow.tryEmit(Unit)
            }
        }
        ContextCompat.registerReceiver(context, eventReceiver!!, filter, ContextCompat.RECEIVER_EXPORTED)
        isListeningEvents = true
    }

    fun stopListeningEvents() {
        if (!isListeningEvents) return
        try {
            eventReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isListeningEvents = false
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
            setPackage(TARGET_PACKAGE)
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
            setPackage(TARGET_PACKAGE)
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

    /** Full activity records of a single day. [shift] is days from today, 0 - today, -1 - yesterday. */
    suspend fun getRecords(shift: Int = 0): String? {
        return query(
            queryAction = ACTION_QUERY_RECORDS,
            responseAction = ACTION_RESPONSE_RECORDS,
            extras = { putExtra(EXTRA_SHIFT, shift) },
        )
    }

    /** Statistics of a single day. [filterType] is one of ACTIVITY, CATEGORY, RECORD_TAG. */
    suspend fun getStatistics(shift: Int = 0, filterType: String = "ACTIVITY"): String? {
        return query(
            queryAction = ACTION_QUERY_STATISTICS,
            responseAction = ACTION_RESPONSE_STATISTICS,
            extras = {
                putExtra(EXTRA_SHIFT, shift)
                putExtra(EXTRA_FILTER_TYPE, filterType)
            },
        )
    }

    private suspend fun query(
        queryAction: String,
        responseAction: String,
        extras: Intent.() -> Unit,
    ): String? {
        val deferred = CompletableDeferred<String?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == responseAction) {
                    deferred.complete(intent.getStringExtra(EXTRA_DATA))
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(responseAction), ContextCompat.RECEIVER_EXPORTED)
        val queryIntent = Intent(queryAction).apply {
            setPackage(TARGET_PACKAGE)
            extras()
        }
        context.sendBroadcast(queryIntent)
        return try {
            withTimeoutOrNull(3000L) { deferred.await() }
        } catch (e: Exception) {
            Log.e("SttBroadcast", "Error running query $queryAction", e)
            null
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    fun startActivity(name: String) {
        val intent = Intent(ACTION_START_ACTIVITY).apply {
            setPackage(TARGET_PACKAGE)
            putExtra(EXTRA_ACTIVITY_NAME, name)
        }
        context.sendBroadcast(intent)
    }

    fun stopActivity(name: String) {
        val intent = Intent(ACTION_STOP_ACTIVITY).apply {
            setPackage(TARGET_PACKAGE)
            putExtra(EXTRA_ACTIVITY_NAME, name)
        }
        context.sendBroadcast(intent)
    }
}

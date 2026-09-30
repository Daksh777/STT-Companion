package com.daksh.sttcompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ServerService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var server: ApplicationEngine? = null
    
    private lateinit var sttManager: SttBroadcastManager
    private lateinit var authManager: AuthManager

    override fun onCreate() {
        super.onCreate()
        sttManager = SttBroadcastManager(this)
        sttManager.startListeningEvents()
        authManager = AuthManager(this)
        createNotificationChannel()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null) {
            startServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        val expectedToken = authManager.token

        server = embeddedServer(CIO, port = 8080) {
            install(CORS) {
                anyHost()
                allowHeader("Authorization")
                allowHeader("Cache-Control")
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
            }
            
            routing {
                intercept(ApplicationCallPipeline.Call) {
                    if (call.request.local.uri.startsWith("/api/")) {
                        val authHeader = call.request.headers["Authorization"] 
                            ?: "Bearer ${call.request.queryParameters["token"]}"
                        if (authHeader != "Bearer $expectedToken") {
                            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing token")
                            finish()
                        }
                    }
                }

                get("/") {
                    call.respondText(getHtmlDashboard(), ContentType.Text.Html)
                }

                get("/api/events") {
                    call.response.cacheControl(CacheControl.NoCache(null))
                    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                        writeStringUtf8("data: connected\n\n")
                        flush()
                        sttManager.updateFlow.collect {
                            writeStringUtf8("data: update\n\n")
                            flush()
                        }
                    }
                }

                get("/api/activities") {
                    val activitiesJson = sttManager.getActivities()
                    if (activitiesJson != null) {
                        call.respondText(activitiesJson, ContentType.Application.Json)
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, "Could not reach STT")
                    }
                }
                
                get("/api/running") {
                    val runningJson = sttManager.getRunning()
                    if (runningJson != null) {
                        call.response.header("X-Server-Time", System.currentTimeMillis().toString())
                        call.respondText(runningJson, ContentType.Application.Json)
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, "Could not reach STT")
                    }
                }
                
                get("/api/records") {
                    val shift = call.request.queryParameters["shift"]?.toIntOrNull() ?: 0
                    val json = sttManager.getRecords(shift)
                    if (json != null) {
                        call.respondText(json, ContentType.Application.Json)
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, "Could not reach STT")
                    }
                }

                get("/api/statistics") {
                    val shift = call.request.queryParameters["shift"]?.toIntOrNull() ?: 0
                    val filterType = call.request.queryParameters["filter"]
                        ?.takeIf { it in setOf("ACTIVITY", "CATEGORY", "RECORD_TAG") }
                        ?: "ACTIVITY"
                    val json = sttManager.getStatistics(shift, filterType)
                    if (json != null) {
                        call.respondText(json, ContentType.Application.Json)
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, "Could not reach STT")
                    }
                }

                post("/api/start") {
                    val activityName = call.request.queryParameters["name"]
                    if (activityName != null) {
                        sttManager.startActivity(activityName)
                        call.respond(HttpStatusCode.OK, "Started")
                    } else {
                        call.respond(HttpStatusCode.BadRequest, "Missing 'name' param")
                    }
                }

                post("/api/stop") {
                    val activityName = call.request.queryParameters["name"]
                    if (activityName != null) {
                        sttManager.stopActivity(activityName)
                        call.respond(HttpStatusCode.OK, "Stopped")
                    } else {
                        call.respond(HttpStatusCode.BadRequest, "Missing 'name' param")
                    }
                }
            }
        }.start(wait = false)
    }

    private fun getHtmlDashboard(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>STT Dashboard</title>
    <style>
        @import url('https://fonts.googleapis.com/css2?family=Roboto:wght@400;500;700&display=swap');
        
        :root {
            --md-bg: #fdfcff;
            --md-on-bg: #1a1c1e;
            --md-surface: #fdfcff;
            --md-surface-container: #f3f3f7;
            --md-primary: #0061a4;
            --md-on-primary: #ffffff;
            --md-secondary-container: #d1e4ff;
            --md-on-secondary-container: #001d36;
            --md-error: #ba1a1a;
            --md-on-error: #ffffff;
            --md-outline: #73777f;
            --md-outline-variant: #c3c7cf;
        }

        body { 
            font-family: 'Roboto', sans-serif; 
            background: var(--md-bg); 
            color: var(--md-on-bg); 
            margin: 0; 
            padding: 24px; 
            -webkit-font-smoothing: antialiased;
        }

        .container { max-width: 600px; margin: 0 auto; }
        
        h1 { font-size: 28px; font-weight: 400; text-align: center; margin-bottom: 32px; display: flex; align-items: center; justify-content: center; gap: 8px;}
        h2 { font-size: 20px; font-weight: 500; margin: 0 0 16px 0; }
        
        .card { 
            background: var(--md-surface-container); 
            border-radius: 24px; 
            padding: 24px; 
            margin-bottom: 24px; 
        }

        .input-group { display: flex; gap: 12px; margin-top: 16px; }
        
        input[type="text"] { 
            flex: 1; 
            padding: 16px; 
            background: var(--md-surface); 
            border: 1px solid var(--md-outline-variant); 
            border-radius: 16px; 
            font-size: 16px;
            color: var(--md-on-bg);
            transition: border 0.2s;
        }
        input[type="text"]:focus { outline: none; border: 2px solid var(--md-primary); padding: 15px; }

        button { 
            background: var(--md-primary); 
            color: var(--md-on-primary); 
            border: none; 
            padding: 10px 24px; 
            border-radius: 100px; 
            font-weight: 500; 
            font-size: 14px;
            cursor: pointer; 
            transition: opacity 0.2s; 
        }
        button:hover { opacity: 0.9; }
        button:active { opacity: 0.8; }
        
        .btn-stop { background: var(--md-error); color: var(--md-on-error); }
        .btn-tonal { background: var(--md-secondary-container); color: var(--md-on-secondary-container); }
        
        .list-item { 
            display: flex; 
            align-items: center; 
            justify-content: space-between; 
            padding: 16px 0; 
            border-bottom: 1px solid var(--md-outline-variant); 
        }
        .list-item:last-child { border-bottom: none; padding-bottom: 0; }
        .list-item:first-child { padding-top: 0; }
        
        .activity-name { font-weight: 500; display: flex; align-items: center; gap: 12px; font-size: 16px; }
        .color-dot { width: 16px; height: 16px; border-radius: 50%; display: inline-block; }
        
        .timer { font-family: monospace; font-size: 15px; color: var(--md-outline); min-width: 75px; display: inline-block; }
        
        #authSection, #appSection { display: none; }
        
        .tabs { display: flex; gap: 8px; margin-bottom: 24px; }
        .tab { flex: 1; background: var(--md-surface-container); color: var(--md-on-bg); padding: 12px 0; }
        .tab.active { background: var(--md-secondary-container); color: var(--md-on-secondary-container); }

        .date-nav { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
        .date-nav button { width: 44px; height: 44px; padding: 0; font-size: 20px; }
        .date-label { font-size: 18px; font-weight: 500; }
        .filter-select { width: 100%; padding: 12px; margin-bottom: 16px; border-radius: 16px; border: 1px solid var(--md-outline-variant); background: var(--md-surface); color: var(--md-on-bg); font-size: 14px; }

        .note { margin: -12px 8px 24px; font-size: 12px; text-align: center; color: var(--md-outline); }

        .loading { text-align: center; padding: 32px 0; color: var(--md-outline); }

        .donut-chart { width: 220px; height: 220px; border-radius: 50%; margin: 8px auto 24px; position: relative; }
        .donut-inner { position: absolute; inset: 40px; border-radius: 50%; background: var(--md-surface-container); }

        .stat-row { display: flex; align-items: center; gap: 12px; padding: 16px; border-radius: 16px; margin-bottom: 10px; color: #fff; font-weight: 500; font-size: 16px; }
        .stat-row .stat-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .stat-row .stat-percent { min-width: 48px; text-align: right; padding-left: 12px; border-left: 1px solid rgba(0,0,0,0.2); }
        .stat-total { display: flex; justify-content: space-between; padding: 16px; border-radius: 16px; background: var(--md-outline-variant); font-weight: 500; }

        .record-row { display: flex; align-items: center; gap: 12px; padding: 12px 16px; border-radius: 16px; margin-bottom: 8px; color: #fff; }
        .record-row.untracked { background: var(--md-surface) !important; color: var(--md-outline); border: 1px dashed var(--md-outline-variant); }
        .record-main { flex: 1; min-width: 0; }
        .record-name { font-weight: 500; font-size: 16px; }
        .record-sub { font-size: 13px; opacity: 0.85; }
        .record-duration { font-weight: 500; white-space: nowrap; }

        .status-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; background: var(--md-error); transition: background 0.3s; }
        .status-dot.connected { background: #146c2e; } /* Green color for connected state */
    </style>
</head>
<body>
    <div class="container">
        <h1>Simple Time Tracker <span id="connStatus" class="status-dot"></span></h1>

        <div id="authSection" class="card">
            <h2>Authentication required</h2>
            <p style="margin: 0; color: var(--md-outline);">Enter the Auth PIN displayed in the Companion App.</p>
            <div class="input-group">
                <input type="text" id="tokenInput" placeholder="4-digit PIN" inputmode="numeric" maxlength="4" autocomplete="off">
                <button onclick="saveToken()">Connect</button>
            </div>
        </div>

        <div id="appSection">
            <div class="tabs">
                <button class="tab active" id="tab-track" onclick="showTab('track')">Track</button>
                <button class="tab" id="tab-records" onclick="showTab('records')">Records</button>
                <button class="tab" id="tab-stats" onclick="showTab('stats')">Statistics</button>
            </div>

            <div id="page-track">
                <div class="card">
                    <h2>Running</h2>
                    <div id="runningList" style="color: var(--md-outline);">Loading...</div>
                </div>

                <div class="card">
                    <h2>Activities</h2>
                    <div id="activitiesList" style="color: var(--md-outline);">Loading...</div>
                </div>
                <p class="note">New or renamed activities can take up to a minute to appear. Reload the page to see them right away.</p>
            </div>

            <div id="page-day" style="display: none;">
                <div class="date-nav">
                    <button class="btn-tonal" onclick="changeShift(-1)">&lt;</button>
                    <span class="date-label" id="dateLabel">Today</span>
                    <button class="btn-tonal" onclick="changeShift(1)">&gt;</button>
                </div>

                <div id="page-records" style="display: none;">
                    <div id="recordsList" style="color: var(--md-outline);">Loading...</div>
                </div>

                <div id="page-stats" style="display: none;">
                    <select class="filter-select" id="filterType" onchange="fetchDay(true)">
                        <option value="ACTIVITY">Activity</option>
                        <option value="CATEGORY">Category</option>
                        <option value="RECORD_TAG">Tag</option>
                    </select>
                    <div class="card">
                        <div id="statsChart"></div>
                        <div id="statsList" style="color: var(--md-outline);">Loading...</div>
                    </div>
                </div>
            </div>
            
            <div style="text-align: center;">
                <button class="btn-tonal" onclick="logout()">Disconnect</button>
            </div>
        </div>
    </div>

    <script>
        let token = localStorage.getItem('stt_token');
        let activitiesMap = {};
        let runningTimers = [];
        let clockSkew = 0;
        let eventSource = null;
        let clockInterval = null;
        let activitiesCache = null;
        let activitiesFetchedAt = 0;
        const ACTIVITIES_TTL_MS = 60000;
        let lastActionAt = 0;
        let pollInterval = null;
        const POLL_INTERVAL_MS = 5000;
        let currentTab = 'track';
        let currentShift = 0;
        
        if (token) {
            document.getElementById('appSection').style.display = 'block';
            fetchData();
            setupSSE();
            startLocalClock();
            startPolling();
        } else {
            document.getElementById('authSection').style.display = 'block';
        }

        function saveToken() {
            const val = document.getElementById('tokenInput').value.trim();
            if(val) {
                localStorage.setItem('stt_token', val);
                location.reload();
            }
        }
        
        function logout() {
            localStorage.removeItem('stt_token');
            location.reload();
        }

        async function api(path, options = {}) {
            options.headers = Object.assign({}, options.headers, {'Authorization': 'Bearer ' + token});
            const res = await fetch('/api' + path, options);
            if (res.status === 401) {
                alert("Invalid Token");
                logout();
            }
            if(!res.ok) throw new Error("API Error");
            return res;
        }
        
        function setupSSE() {
            if(eventSource) eventSource.close();
            eventSource = new EventSource('/api/events?token=' + encodeURIComponent(token));
            eventSource.onopen = () => {
                document.getElementById('connStatus').className = 'status-dot connected';
                document.getElementById('connStatus').title = 'Connected';
            };
            eventSource.onerror = () => {
                document.getElementById('connStatus').className = 'status-dot';
                document.getElementById('connStatus').title = 'Disconnected';
            };
            eventSource.onmessage = (e) => {
                if(e.data === 'update') {
                    fetchData();
                    if (currentTab !== 'track') fetchDay();
                }
            };
        }
        
        function updateClocks() {
            const now = Date.now() + clockSkew;
            runningTimers.forEach(r => {
                const el = document.getElementById('timer-' + r.id);
                if(el) {
                    const durationMs = now - r.startedAt;
                    const h = Math.floor(durationMs / 3600000);
                    const m = Math.floor((durationMs % 3600000) / 60000);
                    const s = Math.floor((durationMs % 60000) / 1000);
                    el.innerText = h.toString().padStart(2,'0') + ':' + m.toString().padStart(2,'0') + ':' + s.toString().padStart(2,'0');
                }
            });
        }
        
        function startLocalClock() {
            if(clockInterval) clearInterval(clockInterval);
            clockInterval = setInterval(updateClocks, 1000);
        }

        async function fetchData() {
            const startedAt = Date.now();
            try {
                // Activities rarely change and are independent from running timers,
                // so they are cached and loaded in parallel with running.
                const needActivities = !activitiesCache || Date.now() - activitiesFetchedAt > ACTIVITIES_TTL_MS;
                const [activities, runRes] = await Promise.all([
                    needActivities ? api('/activities').then(res => res.json()) : Promise.resolve(activitiesCache),
                    api('/running'),
                ]);
                if (needActivities) {
                    activitiesCache = activities;
                    activitiesFetchedAt = Date.now();
                }
                const serverTimeStr = runRes.headers.get("X-Server-Time");
                if (serverTimeStr) {
                    clockSkew = parseInt(serverTimeStr) - Date.now();
                }
                const running = await runRes.json();
                // Drop results that were requested before a local start/stop, they are outdated.
                if (startedAt < lastActionAt) return;
                renderTrack(activities, running);
            } catch (e) {
                console.error(e);
            }
        }

        function renderTrack(activities, running) {
            activitiesMap = {};
            activities.forEach(a => { activitiesMap[a.id] = a; });
            runningTimers = running;

            // Running activities are shown in the running section only, until stopped.
            const runningIds = new Set(running.map(r => r.id));
            let actHtml = '';
            activities.filter(a => !runningIds.has(a.id)).forEach(a => {
                let hex = (a.color & 0xFFFFFF).toString(16).padStart(6, '0');
                actHtml += '<div class="list-item">' +
                    '<span class="activity-name"><span class="color-dot" style="background:#' + hex + '"></span>' + a.name + '</span>' +
                    '<button onclick="startActivity(\'' + a.name + '\')">Start</button>' +
                '</div>';
            });
            document.getElementById('activitiesList').innerHTML = actHtml || 'No activities to start.';

            let runHtml = '';
            running.forEach(r => {
                const act = activitiesMap[r.id] || { name: 'Unknown', color: 0 };
                let hex = (act.color & 0xFFFFFF).toString(16).padStart(6, '0');
                runHtml += '<div class="list-item">' +
                    '<span class="activity-name"><span class="color-dot" style="background:#' + hex + '"></span>' + act.name + ' <span class="timer" id="timer-' + r.id + '">00:00:00</span></span>' +
                    '<button class="btn-stop" onclick="stopActivity(\'' + act.name + '\')">Stop</button>' +
                '</div>';
            });
            document.getElementById('runningList').innerHTML = runHtml || 'Nothing running right now.';
            updateClocks();
        }

        // Running records can be edited in the app without any event, so refresh them periodically.
        function startPolling() {
            if (pollInterval) clearInterval(pollInterval);
            pollInterval = setInterval(() => {
                if (!document.hidden && currentTab === 'track' && Date.now() - lastActionAt > 3000) fetchData();
            }, POLL_INTERVAL_MS);
            document.addEventListener('visibilitychange', () => {
                if (!document.hidden) fetchData();
            });
        }

        function showTab(tab) {
            currentTab = tab;
            ['track', 'records', 'stats'].forEach(t => {
                document.getElementById('tab-' + t).classList.toggle('active', t === tab);
            });
            const isDay = tab !== 'track';
            document.getElementById('page-track').style.display = isDay ? 'none' : 'block';
            document.getElementById('page-day').style.display = isDay ? 'block' : 'none';
            document.getElementById('page-records').style.display = tab === 'records' ? 'block' : 'none';
            document.getElementById('page-stats').style.display = tab === 'stats' ? 'block' : 'none';
            if (isDay) fetchDay(true);
        }

        function changeShift(delta) {
            currentShift += delta;
            fetchDay(true);
        }

        function showLoading() {
            const loading = '<div class="loading">Loading...</div>';
            if (currentTab === 'records') {
                document.getElementById('recordsList').innerHTML = loading;
            } else if (currentTab === 'stats') {
                document.getElementById('statsChart').innerHTML = '';
                document.getElementById('statsList').innerHTML = loading;
            }
        }

        function updateDateLabel() {
            let text;
            if (currentShift === 0) text = 'Today';
            else if (currentShift === -1) text = 'Yesterday';
            else if (currentShift === 1) text = 'Tomorrow';
            else {
                const d = new Date();
                d.setDate(d.getDate() + currentShift);
                text = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
            }
            document.getElementById('dateLabel').innerText = text;
        }

        function fetchDay(withLoading) {
            updateDateLabel();
            if (withLoading) showLoading();
            if (currentTab === 'records') return fetchRecords();
            if (currentTab === 'stats') return fetchStatistics();
        }

        function esc(s) {
            return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
        }

        function colorHex(color) {
            return '#' + ((color || 0) & 0xFFFFFF).toString(16).padStart(6, '0');
        }

        // Icons are either an emoji or an internal resource name (ic_...), only the former can be shown.
        function iconHtml(icon) {
            if (!icon || /^[a-z0-9_]+${'$'}/i.test(icon)) return '';
            return '<span style="font-size: 20px;">' + esc(icon) + '</span>';
        }

        function formatDuration(ms) {
            const totalMin = Math.floor(ms / 60000);
            const h = Math.floor(totalMin / 60);
            const m = totalMin % 60;
            return h > 0 ? h + 'h ' + m + 'm' : m + 'm';
        }

        function formatTime(ts) {
            const d = new Date(ts);
            return d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0');
        }

        async function fetchRecords() {
            const shift = currentShift;
            try {
                const res = await api('/records?shift=' + shift);
                const records = await res.json();
                if (shift !== currentShift || currentTab !== 'records') return;
                let html = '';
                records.forEach(r => {
                    const untracked = r.type === 'UNTRACKED';
                    const running = r.type === 'RUNNING';
                    const name = untracked ? 'Untracked' : (r.activityName || 'Unknown');
                    const duration = (running ? Date.now() : r.endedAt) - r.startedAt;
                    const tags = (r.tags || []).map(t => {
                        return t.name + (t.numericValue != null ? ' ' + t.numericValue + (t.valueSuffix || '') : '');
                    }).join(', ');
                    html += '<div class="record-row' + (untracked ? ' untracked' : '') + '" style="background:' + colorHex(r.activityColor) + '">' +
                        iconHtml(r.activityIcon) +
                        '<div class="record-main">' +
                            '<div class="record-name">' + esc(name) + '</div>' +
                            '<div class="record-sub">' + formatTime(r.startedAt) + ' - ' + (running ? 'now' : formatTime(r.endedAt)) +
                                (tags ? ' · ' + esc(tags) : '') + '</div>' +
                        '</div>' +
                        '<div class="record-duration">' + formatDuration(duration) + '</div>' +
                    '</div>';
                });
                document.getElementById('recordsList').innerHTML = html || 'No records for this day.';
            } catch (e) {
                console.error(e);
                document.getElementById('recordsList').innerText = 'Could not load records.';
            }
        }

        async function fetchStatistics() {
            const shift = currentShift;
            try {
                const filter = document.getElementById('filterType').value;
                const res = await api('/statistics?shift=' + shift + '&filter=' + filter);
                const stats = (await res.json()).filter(s => s.duration > 0 || s.type !== 'UNTRACKED');
                if (shift !== currentShift || currentTab !== 'stats') return;
                stats.sort((a, b) => b.duration - a.duration);

                const sum = stats.reduce((acc, s) => acc + s.duration, 0);
                const tracked = stats.filter(s => s.type !== 'UNTRACKED').reduce((acc, s) => acc + s.duration, 0);
                const colorOf = s => (s.type === 'UNTRACKED' || s.color == null) ? '#5f6368' : colorHex(s.color);

                let chartHtml = '';
                let listHtml = '';
                if (sum > 0) {
                    let acc = 0;
                    const slices = stats.filter(s => s.duration > 0).map(s => {
                        const start = acc / sum * 100;
                        acc += s.duration;
                        return colorOf(s) + ' ' + start + '% ' + (acc / sum * 100) + '%';
                    });
                    chartHtml = '<div class="donut-chart" style="background: conic-gradient(' + slices.join(', ') + ')"><div class="donut-inner"></div></div>';
                }
                stats.forEach(s => {
                    const pct = sum > 0 ? s.duration / sum * 100 : 0;
                    const pctText = pct > 0 && pct < 1 ? '<1%' : Math.round(pct) + '%';
                    const name = s.type === 'UNTRACKED' ? 'Untracked' : (s.name || 'Unknown');
                    listHtml += '<div class="stat-row" style="background:' + colorOf(s) + '">' +
                        iconHtml(s.icon) +
                        '<span class="stat-name">' + esc(name) + '</span>' +
                        '<span>' + formatDuration(s.duration) + '</span>' +
                        '<span class="stat-percent">' + pctText + '</span>' +
                    '</div>';
                });
                if (listHtml) {
                    listHtml += '<div class="stat-total"><span>Total tracked</span><span>' + formatDuration(tracked) + '</span></div>';
                }
                document.getElementById('statsChart').innerHTML = chartHtml;
                document.getElementById('statsList').innerHTML = listHtml || 'No data for this day.';
            } catch (e) {
                console.error(e);
                document.getElementById('statsList').innerText = 'Could not load statistics.';
            }
        }

        // Updates the lists right away, real state arrives with the next event.
        function applyLocal(name, start) {
            const act = Object.values(activitiesMap).find(a => a.name === name);
            if (!act || !activitiesCache) return;
            lastActionAt = Date.now();
            let running = runningTimers.filter(r => r.id !== act.id);
            if (start) running = running.concat([{ id: act.id, startedAt: Date.now() + clockSkew, tags: [] }]);
            renderTrack(activitiesCache, running);
        }

        async function startActivity(name) {
            applyLocal(name, true);
            try {
                await api('/start?name=' + encodeURIComponent(name), { method: 'POST' });
            } catch (e) {
                lastActionAt = 0;
                fetchData();
            }
        }

        async function stopActivity(name) {
            applyLocal(name, false);
            try {
                await api('/stop?name=' + encodeURIComponent(name), { method: 'POST' });
            } catch (e) {
                lastActionAt = 0;
                fetchData();
            }
        }
    </script>
</body>
</html>
        """.trimIndent()
    }

    override fun onDestroy() {
        sttManager.stopListeningEvents()
        CoroutineScope(Dispatchers.IO).launch { server?.stop(1000, 2000) }
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "stt_server",
                "STT Server",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "stt_server")
            .setContentTitle("STT Companion")
            .setContentText("Local server is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}

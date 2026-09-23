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
        
        .status-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; background: var(--md-error); transition: background 0.3s; }
        .status-dot.connected { background: #146c2e; } /* Green color for connected state */
    </style>
</head>
<body>
    <div class="container">
        <h1>Simple Time Tracker <span id="connStatus" class="status-dot"></span></h1>

        <div id="authSection" class="card">
            <h2>Authentication required</h2>
            <p style="margin: 0; color: var(--md-outline);">Enter the Auth Token displayed in the Companion App.</p>
            <div class="input-group">
                <input type="text" id="tokenInput" placeholder="Auth Token" autocomplete="off">
                <button onclick="saveToken()">Connect</button>
            </div>
        </div>

        <div id="appSection">
            <div class="card">
                <h2>Running</h2>
                <div id="runningList" style="color: var(--md-outline);">Loading...</div>
            </div>

            <div class="card">
                <h2>Activities</h2>
                <div id="activitiesList" style="color: var(--md-outline);">Loading...</div>
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
        
        if (token) {
            document.getElementById('appSection').style.display = 'block';
            fetchData();
            setupSSE();
            startLocalClock();
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
            try {
                const actRes = await api('/activities');
                const activities = await actRes.json();
                
                activitiesMap = {};
                let actHtml = '';
                activities.forEach(a => {
                    activitiesMap[a.id] = a;
                    let hex = (a.color & 0xFFFFFF).toString(16).padStart(6, '0');
                    actHtml += '<div class="list-item">' +
                        '<span class="activity-name"><span class="color-dot" style="background:#' + hex + '"></span>' + a.name + '</span>' +
                        '<button onclick="startActivity(\'' + a.name + '\')">Start</button>' +
                    '</div>';
                });
                document.getElementById('activitiesList').innerHTML = actHtml || 'No activities found.';

                const runRes = await api('/running');
                const serverTimeStr = runRes.headers.get("X-Server-Time");
                if (serverTimeStr) {
                    clockSkew = parseInt(serverTimeStr) - Date.now();
                }
                
                const running = await runRes.json();
                runningTimers = running;
                
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
            } catch (e) {
                console.error(e);
            }
        }

        async function startActivity(name) {
            await api('/start?name=' + encodeURIComponent(name), { method: 'POST' });
        }

        async function stopActivity(name) {
            await api('/stop?name=' + encodeURIComponent(name), { method: 'POST' });
        }
    </script>
</body>
</html>
        """.trimIndent()
    }

    override fun onDestroy() {
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

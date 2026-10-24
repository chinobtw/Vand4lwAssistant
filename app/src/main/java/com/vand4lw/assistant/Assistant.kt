package com.vand4lw.assistant

import android.Manifest
import android.app.SearchManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Cerebro: le manda la orden a Gemini y ejecuta las acciones que devuelve. */
class Assistant(private val svc: ControlService) {

    companion object {
        const val MODEL = "gemini-3.6-flash"
        private const val MAX_HISTORY = 12
    }

    class Plan(val say: String, val actions: List<JSONObject>)

    private val ctx: Context = svc
    private val history = ArrayDeque<Pair<String, String>>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    // ───────────────────────── Gemini ─────────────────────────

    suspend fun ask(userText: String): Plan = withContext(Dispatchers.IO) {
        val key = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("key", "").orEmpty()
        if (key.isBlank()) throw IllegalStateException("Falta la API key de Gemini (cargala en la app)")
        val raw = callGemini(key, userText)
        val plan = parse(raw)
        history.addLast("user" to userText)
        history.addLast("model" to raw)
        while (history.size > MAX_HISTORY) history.removeFirst()
        plan
    }

    private fun content(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun callGemini(key: String, userText: String): String {
        val now = SimpleDateFormat("EEEE d 'de' MMMM yyyy, HH:mm", Locale("es", "AR")).format(Date())
        val contents = JSONArray()
        for ((role, text) in history) contents.put(content(role, text))
        contents.put(content("user", userText))

        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(now)))))
            .put("contents", contents)
            .put(
                "generation_config", JSONObject()
                    .put("temperature", 0.2)
                    .put("response_mime_type", "application/json")
                    .put("thinking_level", "low")
            )

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                    .getOrDefault(text.take(200))
                throw RuntimeException("Gemini ${resp.code}: $msg")
            }
            val cands = JSONObject(text).optJSONArray("candidates")
            if (cands == null || cands.length() == 0) throw RuntimeException("Gemini no devolvió respuesta (¿bloqueada?)")
            val parts = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
            }
            sb.toString()
        }
    }

    private fun parse(raw: String): Plan {
        try {
            val t = raw.replace("```json", "").replace("```", "").trim()
            val actions = mutableListOf<JSONObject>()
            var say = ""
            val arr: JSONArray
            if (t.startsWith("[")) {
                arr = JSONArray(t)
            } else {
                val o = JSONObject(t)
                say = o.optString("say")
                arr = o.optJSONArray("actions") ?: JSONArray()
            }
            for (i in 0 until arr.length()) actions.add(arr.getJSONObject(i))
            return Plan(say, actions)
        } catch (e: Exception) {
            throw RuntimeException("Respuesta rara de Gemini: ${raw.take(120)}")
        }
    }

    private fun systemPrompt(now: String) = """
Sos el asistente del celular de un usuario argentino (Android 8.1). Hablás en español rioplatense, corto y natural.
Ahora es: $now.

Respondé SIEMPRE con un único objeto JSON, sin markdown:
{"say":"lo que decís en voz alta, máximo 2 frases cortas","actions":[acciones en orden]}

Reglas:
- Si te pide hacer algo en el celu, poné las acciones y en "say" confirmá brevemente.
- Si te hace una pregunta o charla, contestá en "say" y dejá "actions":[].
- Si algo no se puede con las acciones disponibles, decilo en "say" y no inventes acciones.
- No pidas confirmación, ejecutá.
- Después de open_app, si tenés que tocar o escribir algo, poné antes {"action":"wait","ms":1500}.

Acciones disponibles:
{"action":"open_app","name":"nombre de la app como lo dice el usuario"}
{"action":"play_spotify","query":"canción o artista"}
{"action":"search_youtube","query":"texto"}
{"action":"search_web","query":"texto"}
{"action":"navigate","query":"lugar o dirección"}
{"action":"volume","value":0-100}
{"action":"brightness","value":0-100}
{"action":"media","type":"play|pause|next|previous"}
{"action":"call","contact":"nombre o número"}
{"action":"sms","contact":"nombre o número","text":"mensaje"}
{"action":"alarm","hour":0-23,"minute":0-59,"label":"opcional"}
{"action":"timer","seconds":número,"label":"opcional"}
{"action":"torch","on":true|false}
{"action":"wifi","on":true|false}
{"action":"bluetooth","on":true|false}
{"action":"global","type":"back|home|recents|notifications|quick_settings"}
{"action":"scroll","direction":"up|down"}
{"action":"click_text","text":"texto visible del botón"}
{"action":"type_text","text":"texto a escribir en el campo enfocado"}
{"action":"wait","ms":número}

Ejemplo: "reproducí Duki en Spotify y subí el volumen al 80"
{"say":"Dale, pongo Duki y subo el volumen.","actions":[{"action":"play_spotify","query":"Duki"},{"action":"volume","value":80}]}
""".trimIndent()

    // ───────────────────────── Ejecución ─────────────────────────

    suspend fun execute(actions: List<JSONObject>, warn: (String) -> Unit) {
        for (a in actions) {
            val name = a.optString("action")
            try {
                exec(name, a)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                warn("⚠ $name: ${e.message}")
            }
            if (name != "wait") delay(500)
        }
    }

    private suspend fun exec(name: String, a: JSONObject) {
        when (name) {
            "open_app" -> openApp(a.optString("name"))
            "play_spotify" -> playSpotify(a.optString("query"))
            "search_youtube", "play_youtube" -> searchYoutube(a.optString("query"))
            "search_web" -> view("https://www.google.com/search?q=" + Uri.encode(a.optString("query")))
            "navigate" -> navigate(a.optString("query"))
            "volume" -> setVolume(a.optInt("value"))
            "brightness" -> setBrightness(a.optInt("value"))
            "media" -> media(a.optString("type"))
            "call" -> call(a.optString("contact"))
            "sms" -> sms(a.optString("contact"), a.optString("text"))
            "alarm" -> alarm(a.optInt("hour"), a.optInt("minute"), a.optString("label"))
            "timer" -> timer(a.optInt("seconds"), a.optString("label"))
            "torch" -> torch(a.optBoolean("on", true))
            "wifi" -> wifi(a.optBoolean("on", true))
            "bluetooth" -> bluetooth(a.optBoolean("on", true))
            "global" -> svc.global(a.optString("type"))
            "scroll" -> svc.scroll(a.optString("direction"))
            "click_text" -> {
                val t = a.optString("text")
                if (!svc.clickOnText(t)) throw RuntimeException("no encontré '$t' en pantalla")
            }
            "type_text" -> {
                if (!svc.typeText(a.optString("text"))) throw RuntimeException("no hay campo de texto para escribir")
            }
            "wait" -> delay(a.optLong("ms", 1000).coerceIn(0L, 10_000L))
            else -> throw RuntimeException("acción desconocida")
        }
    }

    private fun start(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun view(url: String) = start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), "")
            .trim()

    // ── Apps y medios ──

    private fun openApp(spoken: String) {
        val wanted = norm(spoken)
        if (wanted.isEmpty()) throw RuntimeException("falta el nombre de la app")
        if (wanted in setOf("ajustes", "configuracion", "settings")) {
            start(Intent(Settings.ACTION_SETTINGS))
            return
        }
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val match = apps.firstOrNull { norm(it.loadLabel(pm).toString()) == wanted }
            ?: apps.firstOrNull { norm(it.loadLabel(pm).toString()).contains(wanted) }
            ?: apps.firstOrNull { val l = norm(it.loadLabel(pm).toString()); l.length > 2 && wanted.contains(l) }
        val launch = match?.activityInfo?.packageName?.let { pm.getLaunchIntentForPackage(it) }
            ?: throw RuntimeException("no encontré la app '$spoken'")
        start(launch)
    }

    private fun playSpotify(query: String) {
        try {
            start(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage("com.spotify.music")
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(SearchManager.QUERY, query)
            })
        } catch (e: Exception) {
            try {
                view("spotify:search:" + Uri.encode(query))
            } catch (e2: Exception) {
                view("https://open.spotify.com/search/" + Uri.encode(query))
            }
        }
    }

    private fun searchYoutube(query: String) {
        try {
            start(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", query))
        } catch (e: Exception) {
            view("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        }
    }

    private fun navigate(query: String) {
        try {
            start(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(query)))
                    .setPackage("com.google.android.apps.maps")
            )
        } catch (e: Exception) {
            view("geo:0,0?q=" + Uri.encode(query))
        }
    }

    private fun media(type: String) {
        val code = when (type) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ── Sistema ──

    private fun setVolume(percent: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, percent.coerceIn(0, 100) * max / 100, AudioManager.FLAG_SHOW_UI)
    }

    private fun setBrightness(percent: Int) {
        if (!Settings.System.canWrite(ctx)) {
            start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            throw RuntimeException("dame el permiso 'Modificar ajustes del sistema' y repetí la orden")
        }
        val v = (percent.coerceIn(0, 100) * 255 / 100).coerceAtLeast(10)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
    }

    private fun torch(on: Boolean) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw RuntimeException("este celu no tiene flash")
        cm.setTorchMode(id, on)
    }

    @Suppress("DEPRECATION")
    private fun wifi(on: Boolean) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.setWifiEnabled(on)) throw RuntimeException("no pude cambiar el wifi")
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun bluetooth(on: Boolean) {
        val ad = BluetoothAdapter.getDefaultAdapter() ?: throw RuntimeException("este celu no tiene bluetooth")
        val ok = if (on) ad.enable() else ad.disable()
        if (!ok && ad.isEnabled != on) throw RuntimeException("no pude cambiar el bluetooth")
    }

    private fun alarm(hour: Int, minute: Int, label: String) {
        start(Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        })
    }

    private fun timer(seconds: Int, label: String) {
        start(Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86400))
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        })
    }

    // ── Llamadas y SMS ──

    @Suppress("DEPRECATION")
    private fun resolveNumber(who: String): String {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 6) return digits
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw RuntimeException("falta el permiso de contactos")
        }
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cur = ctx.contentResolver.query(
            phone,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$who%"),
            ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED + " DESC"
        )
        cur?.use { if (it.moveToFirst()) return it.getString(0) }
        throw RuntimeException("no encontré a '$who' en tus contactos")
    }

    private fun call(who: String) {
        val num = resolveNumber(who)
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        start(Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", num, null)))
    }

    private fun sms(who: String, text: String) {
        val num = resolveNumber(who)
        start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", num, null)).putExtra("sms_body", text))
    }
}

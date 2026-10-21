package dev.tvlauncher.muse.link

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import dev.tvlauncher.BuildConfig
import dev.tvlauncher.apps.HdmiInputs
import org.json.JSONObject
import java.util.Base64

/**
 * What the Muse may do on this TV (`commands_v2`): open apps and inputs, control playback
 * and volume, show a message, report status. Deliberately no shell, files or installs.
 * Runs on the link's invoke threads.
 */
class TvCommands(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    fun run(command: String, params: JSONObject, @Suppress("UNUSED_PARAMETER") timeoutMs: Long?): Map<String, Any?> = when (command) {
        "tv.list_apps" -> ok("apps" to launchableApps().map { it.first })
        "tv.open_app" -> openApp(params.opt("name") as? String ?: "")
        "tv.open_input" -> openInput((params.opt("port") as? Number)?.toInt())
        "tv.home" -> start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName), "home")
        "tv.media" -> media(params.opt("action") as? String ?: "")
        "tv.volume" -> volume(params.opt("action") as? String ?: "", (params.opt("steps") as? Number)?.toInt() ?: 1)
        "tv.show_message" -> showMessage(params.opt("text") as? String ?: "")
        "tv.screenshot" -> screenshot()
        "device.health" -> health()
        else -> failure("unknown command")
    }

    private fun openApp(name: String): Map<String, Any?> {
        val apps = launchableApps()
        val index = AppMatch.best(name, apps.map { it.first }) ?: return failure(
            "no app called \"${name.take(60)}\"; installed: ${apps.joinToString { it.first }}",
        )
        val (label, packageName) = apps[index]
        val intent = context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return failure("$label can't be opened")
        return start(intent, label)
    }

    private fun openInput(port: Int?): Map<String, Any?> {
        if (port == null || port !in HdmiInputs.PORTS) return failure("port must be 1 to ${HdmiInputs.PORTS.last}")
        return if (HdmiInputs.open(context, port)) ok("opened" to HdmiInputs.label(port)) else failure("HDMI $port isn't available")
    }

    private fun start(intent: Intent, label: String): Map<String, Any?> = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        ok("opened" to label)
    } catch (e: Exception) {
        failure("couldn't open $label: ${e.javaClass.simpleName}")
    }

    private fun media(action: String): Map<String, Any?> {
        val key = MEDIA_KEYS[action] ?: return failure("action must be one of ${MEDIA_KEYS.keys.joinToString()}")
        val audio = audio ?: return failure("no audio service")
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return ok("sent" to action)
    }

    private fun volume(action: String, steps: Int): Map<String, Any?> {
        val audio = audio ?: return failure("no audio service")
        val stream = AudioManager.STREAM_MUSIC
        when (action) {
            "up", "down" -> repeat(steps.coerceIn(1, 10)) {
                val direction = if (action == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                audio.adjustStreamVolume(stream, direction, if (it == 0) AudioManager.FLAG_SHOW_UI else 0)
            }
            "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            else -> return failure("action must be up, down, mute or unmute")
        }
        return ok(
            "volume" to audio.getStreamVolume(stream),
            "max" to audio.getStreamMaxVolume(stream),
            "muted" to audio.isStreamMute(stream),
        )
    }

    private fun showMessage(text: String): Map<String, Any?> {
        val message = text.trim().take(MAX_MESSAGE_CHARS)
        if (message.isEmpty()) return failure("text is empty")
        main.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        return ok("shown" to true)
    }

    private fun screenshot(): Map<String, Any?> = TvScreenshot.capture().fold(
        { (jpeg, width, height) ->
            ok(
                "mime_type" to "image/jpeg",
                "width" to width,
                "height" to height,
                "data_base64" to Base64.getEncoder().encodeToString(jpeg),
            )
        },
        { failure(it.message ?: "screenshot failed") },
    )

    private fun health(): Map<String, Any?> {
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        return ok(
            "uptime_s" to SystemClock.elapsedRealtime() / 1000,
            "memory_available_mb" to memory.availMem / MB,
            "memory_total_mb" to memory.totalMem / MB,
            "low_memory" to memory.lowMemory,
            "android" to Build.VERSION.RELEASE,
            "model" to Build.MODEL,
            "launcher_version" to BuildConfig.VERSION_NAME,
        )
    }

    /** (label, package) for every TV-launchable app but this launcher, by label. */
    private fun launchableApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    companion object {
        /** Commands that put something else on screen; the chat overlay gets out of the way. */
        val SCREEN_CHANGES = setOf("tv.open_app", "tv.open_input", "tv.home")

        private const val MAX_MESSAGE_CHARS = 200
        private const val MB = 1024 * 1024L

        private val MEDIA_KEYS = linkedMapOf(
            "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
            "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
            "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
            "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
            "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            "rewind" to KeyEvent.KEYCODE_MEDIA_REWIND,
            "fast_forward" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        )

        /** The `commands_v2` the TV registers. */
        val SPECS: Map<String, Any> = linkedMapOf(
            "tv.list_apps" to spec("List the apps on this TV that can be opened."),
            "tv.open_app" to spec(
                "Open an app on this TV by name, e.g. YouTube or Netflix.",
                required = mapOf("name" to param("string", "The app's name as the TV shows it.")),
            ),
            "tv.open_input" to spec(
                "Switch this TV to one of its HDMI inputs, e.g. the game console.",
                required = mapOf("port" to param("integer", "HDMI port number, 1 to 4.")),
            ),
            "tv.home" to spec("Go to this TV's home screen."),
            "tv.media" to spec(
                "Control what's playing on this TV.",
                required = mapOf("action" to param("string", "One of: ${MEDIA_KEYS.keys.joinToString()}.")),
            ),
            "tv.volume" to spec(
                "Change this TV's volume and report the new level.",
                required = mapOf("action" to param("string", "up, down, mute or unmute.")),
                optional = mapOf("steps" to param("integer", "Steps for up or down. Default 1, max 10.")),
            ),
            "tv.show_message" to spec(
                "Show a short message on this TV's screen for a few seconds.",
                required = mapOf("text" to param("string", "The message, up to $MAX_MESSAGE_CHARS characters.")),
            ),
            "tv.screenshot" to spec(
                "See what's on this TV's screen right now: returns a JPEG screenshot (base64). " +
                    "Protected video such as Netflix shows as black.",
            ),
            "device.health" to spec("This TV's status: uptime, memory, Android version and launcher version."),
        )

        private fun spec(description: String, required: Map<String, Any> = emptyMap(), optional: Map<String, Any> = emptyMap()) =
            linkedMapOf("description" to description, "required" to required, "optional" to optional)

        private fun param(type: String, description: String) = linkedMapOf("type" to type, "description" to description)

        private fun ok(vararg payload: Pair<String, Any?>): Map<String, Any?> = linkedMapOf("ok" to true, "payload" to linkedMapOf(*payload))

        private fun failure(message: String): Map<String, Any?> = linkedMapOf("ok" to false, "error" to message)
    }
}

/** Picks the app a spoken or typed name means. */
object AppMatch {
    /**
     * Index of the best label for [query]: an exact match ignoring case, spaces and symbols,
     * then a label starting with it, then one containing it; the shortest wins a tie.
     */
    fun best(query: String, labels: List<String>): Int? {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return null
        val names = labels.map(::normalize)
        for (rule in listOf<(String) -> Boolean>({ it == wanted }, { it.startsWith(wanted) }, { it.contains(wanted) })) {
            names.indices.filter { rule(names[it]) }.minByOrNull { names[it].length }?.let { return it }
        }
        return null
    }

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
}

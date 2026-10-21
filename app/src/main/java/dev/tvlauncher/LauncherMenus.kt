package dev.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import dev.tvlauncher.apps.AppEntry
import dev.tvlauncher.apps.AppOrderStore
import dev.tvlauncher.apps.HdmiInputs
import dev.tvlauncher.wallpaper.WallpaperController
import dev.tvlauncher.weather.WeatherController
import kotlin.math.roundToInt

/** The launcher's dialogs: the gear menu, an app's and a pin's long-press menus, and the settings prompts. */
internal class LauncherMenus(private val activity: Activity, private val host: Host) {
    /** What the dialogs read and change on the home screen. */
    interface Host {
        val allApps: List<AppEntry>
        val rowPrefs: AppOrderStore
        val wallpaper: WallpaperController
        val weather: WeatherController

        fun startMove(packageName: String)

        /** Re-applies order, hidden and pinned apps to the row. */
        fun redisplayApps()

        fun reloadPinned()

        fun focusRow()

        fun pinLabel(key: String): String
    }

    fun showMenu() {
        val actions = buildList<Pair<String, () -> Unit>> {
            add(activity.getString(R.string.menu_next_wallpaper) to { host.wallpaper.next() })
            add(activity.getString(R.string.menu_weather_location) to ::editWeatherPlace)
            if (host.allApps.any { it.packageName in host.rowPrefs.hidden }) {
                add(activity.getString(R.string.menu_hidden_apps) to ::editHiddenApps)
            }
            add(activity.getString(R.string.menu_pin_hdmi) to ::pinHdmiInput)
            add(activity.getString(R.string.menu_system_settings) to { SettingsButton.open(activity) })
        }
        dialog()
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    fun showAppMenu(app: AppEntry) {
        val items = arrayOf(
            activity.getString(R.string.app_move),
            activity.getString(R.string.app_hide),
            activity.getString(R.string.app_pin),
            activity.getString(R.string.app_info),
        )
        dialog()
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> host.startMove(app.packageName)
                    1 -> {
                        host.rowPrefs.hidden = host.rowPrefs.hidden + app.packageName
                        host.redisplayApps()
                    }
                    2 -> {
                        host.rowPrefs.pinned = host.rowPrefs.pinned + app.packageName
                        host.reloadPinned()
                        host.redisplayApps()
                    }
                    3 -> try {
                        activity.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "app info unavailable", e)
                    }
                }
            }
            .show()
    }

    fun showPinnedMenu(key: String) {
        dialog()
            .setTitle(host.pinLabel(key))
            .setItems(arrayOf(activity.getString(R.string.app_unpin))) { _, _ ->
                host.rowPrefs.pinned = host.rowPrefs.pinned - key
                host.reloadPinned()
                host.redisplayApps()
                host.focusRow()
            }
            .show()
    }

    /** Gear → Pin HDMI input: pick a port, then its logo. */
    private fun pinHdmiInput() {
        val ports = HdmiInputs.PORTS.filter { HdmiInputs.key(it) !in host.rowPrefs.pinned }
        if (ports.isEmpty()) return
        dialog()
            .setTitle(R.string.menu_pin_hdmi)
            .setItems(ports.map { HdmiInputs.label(it) }.toTypedArray()) { _, i ->
                val port = ports[i]
                val logos = HdmiInputs.LOGOS.entries.toList()
                dialog()
                    .setTitle(HdmiInputs.label(port))
                    .setItems(logos.map { it.value.second }.toTypedArray()) { _, j ->
                        host.rowPrefs.setHdmiLogo(port, logos[j].key)
                        host.rowPrefs.pinned = host.rowPrefs.pinned + HdmiInputs.key(port)
                        host.reloadPinned()
                    }
                    .show()
            }
            .show()
    }

    private fun editHiddenApps() {
        val hiddenApps = host.allApps.filter { it.packageName in host.rowPrefs.hidden }
        if (hiddenApps.isEmpty()) return
        val stillHidden = BooleanArray(hiddenApps.size) { true }
        dialog()
            .setTitle(R.string.menu_hidden_apps)
            .setMultiChoiceItems(hiddenApps.map { it.label }.toTypedArray(), stillHidden) { _, i, checked ->
                stillHidden[i] = checked
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val shown = hiddenApps.filterIndexed { i, _ -> !stillHidden[i] }.map { it.packageName }
                host.rowPrefs.hidden = host.rowPrefs.hidden - shown.toSet()
                host.redisplayApps()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editWeatherPlace() = prompt(
        title = R.string.menu_weather_location,
        initial = host.weather.placeQuery,
        hint = activity.getString(R.string.weather_place_hint),
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
    ) { query ->
        host.weather.setPlace(query) { name ->
            val message = when {
                name != null -> name
                query.isBlank() -> null
                else -> activity.getString(R.string.weather_place_not_found)
            }
            message?.let { Toast.makeText(activity, it, Toast.LENGTH_SHORT).show() }
        }
    }

    /** One text field with Save and Cancel. */
    private fun prompt(title: Int, initial: String, hint: String?, inputType: Int, onSave: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(initial)
            if (hint != null) this.hint = hint
            this.inputType = inputType
            setSelection(text.length)
        }
        val box = FrameLayout(activity).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        dialog()
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> onSave(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dialog() = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "Launcher"
    }
}

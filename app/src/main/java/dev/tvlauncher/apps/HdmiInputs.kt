package dev.tvlauncher.apps

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.util.Log
import dev.tvlauncher.R

/**
 * HDMI inputs as pins. A pin key is "hdmi:<port>"; opening it asks the TV app
 * (TCL's TVActivity handles the standard passthrough URI) to show that input.
 */
object HdmiInputs {
    private const val PREFIX = "hdmi:"
    val PORTS = 1..4

    /** Logos an HDMI pin can use: key → (drawable, name). */
    val LOGOS = linkedMapOf(
        "xbox" to (R.drawable.logo_xbox to "Xbox"),
        "input" to (R.drawable.ic_input to "HDMI"),
    )

    fun key(port: Int) = "$PREFIX$port"

    fun port(key: String): Int? = key.removePrefix(PREFIX).takeIf { key.startsWith(PREFIX) }?.toIntOrNull()

    fun label(port: Int) = "HDMI $port"

    fun open(context: Context, port: Int): Boolean {
        val id = inputId(context, port) ?: return false
        return try {
            context.startActivity(
                // TCL's TV app matches on the channel MIME type, so set it explicitly.
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(id), TvContract.Channels.CONTENT_ITEM_TYPE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: Exception) {
            Log.w("Apps", "could not open HDMI $port", e)
            false
        }
    }

    /**
     * The passthrough input for [port]: matched by its "HDMI n" label, else by
     * position (TCL's HDMI input ids sort in port order).
     */
    private fun inputId(context: Context, port: Int): String? {
        val tim = context.getSystemService(TvInputManager::class.java) ?: return null
        val hdmi = tim.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
        val byLabel = hdmi.firstOrNull {
            Regex("""HDMI\s*$port\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.loadLabel(context))
        }
        return (byLabel ?: hdmi.sortedBy { it.id }.getOrNull(port - 1))?.id
    }
}

package dev.tvlauncher.muse.pairing

import java.security.SecureRandom

/**
 * The TV's Muse identity: a random, locally administered MAC-shaped value made once and
 * kept, never a real hardware address. The node id and the BLE name end in the same six
 * hex digits, which is how the Muse app matches the two.
 */
data class GadgetIdentity(val mac: String) {
    init {
        require(isValidMac(mac)) { "not a lowercase MAC address" }
    }

    val suffix: String get() = mac.replace(":", "").takeLast(6)
    val nodeId: String get() = "homelink-$suffix"
    val deviceId: String get() = "hatch-link:$mac"

    // No separator: the app compares the text after the prefix with the node id's suffix.
    val bleName: String get() = "MuseGadget" + suffix.uppercase()

    companion object {
        private val MAC = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

        fun isValidMac(mac: String) = MAC.matches(mac)

        fun generate(randomBytes: (Int) -> ByteArray = { ByteArray(it).also(SecureRandom()::nextBytes) }): GadgetIdentity {
            val octets = randomBytes(6)
            octets[0] = ((octets[0].toInt() and 0xFC) or 0x02).toByte() // unicast, locally administered
            return GadgetIdentity(octets.joinToString(":") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
        }
    }
}

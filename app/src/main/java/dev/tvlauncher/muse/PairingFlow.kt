package dev.tvlauncher.muse

/** Where the TV is in pairing with the Muse app on a phone. */
sealed interface PairingState {
    /** Advertising as [gadgetName] until [deadlineMillis] (wall clock); [step] is the current stage. */
    data class InProgress(val gadgetName: String, val deadlineMillis: Long, val step: PairingStep) : PairingState

    object Done : PairingState

    data class Failed(val reason: String) : PairingState
}

/** Pairing stages in order; the walkthrough shows earlier ones as done. */
enum class PairingStep { WAITING_FOR_PHONE, SECURING, CONFIRMING, SIGNING_IN }

/**
 * The pairing engine behind the walkthrough (Bluetooth advertising + the Muse
 * pairing handshake). States arrive on the main thread until Done/Failed or [cancel].
 */
interface PairingFlow {
    fun start(onState: (PairingState) -> Unit)

    fun cancel()
}

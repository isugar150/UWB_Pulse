package dev.uwbpulse.monitor.domain

/**
 * Is the phone linked to the car's Bluetooth? Folds the ACL link and the two profiles a car head unit uses
 * (hands-free and audio) into one boolean. A lost ACL link ends every profile, so a missed profile broadcast
 * cannot leave the car "connected" forever. Pure: the Android receiver only feeds it events.
 */
class CarLink {
    enum class Profile { A2DP, HEADSET }
    private var acl = false
    private var a2dp = false
    private var headset = false
    val connected: Boolean get() = acl || a2dp || headset

    /** Each function returns the new [connected] value when it changed, otherwise null. */
    fun acl(up: Boolean): Boolean? = change {
        acl = up
        if (!up) { a2dp = false; headset = false }
    }
    fun profile(profile: Profile, up: Boolean): Boolean? = change {
        when (profile) { Profile.A2DP -> a2dp = up; Profile.HEADSET -> headset = up }
    }
    /** Initial state from the connected-device lists of the two profiles. */
    fun seed(a2dpUp: Boolean, headsetUp: Boolean): Boolean { a2dp = a2dpUp; headset = headsetUp; return connected }

    private fun change(update: () -> Unit): Boolean? {
        val before = connected
        update()
        return connected.takeIf { it != before }
    }
}

/**
 * Car-Bluetooth tracking: while the car is linked the tag is left alone, when it drops the tag is measured until the
 * departure is decided, and the next car link starts over. Distance events still come only from real UWB samples;
 * this only decides when to look. TRACKING is entered only from a real "car was linked, now it is not" change:
 * a start without the car link says nothing about leaving, so it waits (WAITING) and the tag is not touched.
 */
class CarModeMachine(start: Phase = Phase.UNKNOWN, private val carWasLinkedBeforeStart: Boolean = false) {
    enum class Phase { UNKNOWN, CAR_CONNECTED, WAITING, TRACKING, AWAY }
    enum class Action { NONE, ENTER_CAR, START_TRACKING, STOP_TRACKING, WAIT }
    var phase = start; private set

    /** A confirmed car link change. A new car link always wins: it ends any window and means "present". */
    fun car(connected: Boolean): Action = when {
        connected && phase != Phase.CAR_CONNECTED -> { phase = Phase.CAR_CONNECTED; Action.ENTER_CAR }
        !connected && phase == Phase.CAR_CONNECTED -> { phase = Phase.TRACKING; Action.START_TRACKING }
        // A restarted service that knows the car was linked just before it went down and finds it gone missed the departure.
        !connected && phase == Phase.UNKNOWN && carWasLinkedBeforeStart -> { phase = Phase.TRACKING; Action.START_TRACKING }
        !connected && phase == Phase.UNKNOWN -> { phase = Phase.WAITING; Action.WAIT } // no earlier link: not a departure
        else -> Action.NONE
    }
    /** The window decided the user walked away (real exit, or the tag stayed unreachable). Stop measuring until the car links again. */
    fun departed(): Action = if (phase == Phase.TRACKING) { phase = Phase.AWAY; Action.STOP_TRACKING } else Action.NONE

    companion object {
        /** A profile that flaps (audio drops, hands-free follows) must not start a window. */
        const val DISCONNECT_CONFIRM_MILLIS = 3_000L
    }
}

/**
 * What a restarted service should assume about a car link it did not see. The phase is saved at every change and refreshed
 * every [HEARTBEAT_MILLIS] while the car is linked or a window is open, so a recent save means the service went down
 * shortly after having seen the car linked. An old save says nothing about now (a reboot long after parking must not "depart").
 */
object CarRestore {
    const val HEARTBEAT_MILLIS = 60_000L
    const val FRESH_MILLIS = 15 * 60_000L
    data class Start(val phase: CarModeMachine.Phase, val carWasLinked: Boolean)

    fun start(savedPhase: String, savedAtEpochMillis: Long, nowEpochMillis: Long): Start {
        val saved = CarModeMachine.Phase.entries.firstOrNull { it.name == savedPhase }
        val fresh = savedAtEpochMillis > 0 && nowEpochMillis - savedAtEpochMillis in 0..FRESH_MILLIS
        return when {
            (saved == CarModeMachine.Phase.CAR_CONNECTED || saved == CarModeMachine.Phase.TRACKING) && fresh -> Start(CarModeMachine.Phase.UNKNOWN, true)
            saved == CarModeMachine.Phase.AWAY -> Start(CarModeMachine.Phase.AWAY, false) // already left; idle until the car links
            else -> Start(CarModeMachine.Phase.UNKNOWN, false)
        }
    }
}

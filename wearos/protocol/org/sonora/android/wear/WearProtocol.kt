package org.sonora.android.wear

/**
 * The wire between the phone app and the watch app, compiled into both (`:app` and `:wearos` add
 * this directory as a source root), so a key renamed on one side cannot stop matching the other.
 *
 * The watch is a remote, not a second player. The Wearable Data Layer only delivers between apps with the same
 * package name and signing key, so the watch app takes the phone app's application id.
 *
 *  - State, phone → watch, as DataItems ([PATH_NOW_PLAYING], [PATH_QUEUE]); the Data Layer keeps the
 *    last one, so a watch app that opens draws what was playing at once.
 *  - Commands, watch → phone, as messages under [PATH_COMMAND]: the command is the last path
 *    segment, its argument (if any) the UTF-8 payload.
 */
object WearProtocol {
    /** Advertised by the phone app (res/values/wear.xml) so the watch can find it. */
    const val CAPABILITY_PHONE = "lfly_phone"

    /** Advertised by the watch app, so the phone only does the work with one around. */
    const val CAPABILITY_WATCH = "lfly_watch"

    const val PATH_NOW_PLAYING = "/lfly/now_playing"
    const val PATH_QUEUE = "/lfly/queue"
    const val PATH_COMMAND = "/lfly/command"

    // ---- Commands ----
    /** Ask for a fresh [PATH_NOW_PLAYING]. */
    const val CMD_SYNC = "sync"
    const val CMD_TOGGLE = "toggle"
    const val CMD_NEXT = "next"
    const val CMD_PREVIOUS = "previous"
    const val CMD_LIKE = "like"

    /** Payload: signed number of volume steps, e.g. "2" or "-1". */
    const val CMD_VOLUME = "volume"

    /** Ask for a fresh [PATH_QUEUE]. */
    const val CMD_QUEUE = "queue"

    /** Payload: absolute queue index to jump to. */
    const val CMD_SKIP_TO = "skip_to"

    /** Payload: position in ms. */
    const val CMD_SEEK = "seek"

    // ---- PATH_NOW_PLAYING keys ----
    /** False when the phone has nothing queued; nothing below is set then. */
    const val KEY_HAS_TRACK = "has_track"
    const val KEY_TRACK_ID = "track_id"
    const val KEY_TITLE = "title"
    const val KEY_ARTIST = "artist"
    const val KEY_PLAYING = "playing"
    const val KEY_POSITION_MS = "position_ms"

    /** Wall-clock millis [KEY_POSITION_MS] was read at; the watch extrapolates from it while playing. */
    const val KEY_POSITION_AT = "position_at"
    const val KEY_DURATION_MS = "duration_ms"
    const val KEY_HAS_PREVIOUS = "has_previous"
    const val KEY_HAS_NEXT = "has_next"
    const val KEY_LIKED = "liked"

    /** A small JPEG of the cover, as an Asset. */
    const val KEY_ART = "art"
    const val KEY_VOLUME = "volume"
    const val KEY_VOLUME_MAX = "volume_max"

    // ---- PATH_QUEUE keys ----
    /** DataMap list, one per row: [KEY_TITLE], [KEY_ARTIST], [KEY_INDEX]. */
    const val KEY_ITEMS = "items"
    const val KEY_INDEX = "index"
    const val KEY_CURRENT_INDEX = "current_index"

    /** Side of the cover JPEG on [PATH_NOW_PLAYING]. */
    const val ART_PX = 240

    // ---- Installing the watch app from the phone ----

    /**
     * Phone → watch message: say which build is installed. The watch answers with a
     * [PATH_WATCH_VERSION] DataItem of its own ([KEY_VERSION_CODE], [KEY_VERSION_NAME]).
     */
    const val PATH_WATCH_VERSION_ASK = "/lfly/watch/version_ask"
    const val PATH_WATCH_VERSION = "/lfly/watch/version"
    const val KEY_VERSION_CODE = "version_code"
    const val KEY_VERSION_NAME = "version_name"

    /** Phone → watch channel carrying the watch APK; the watch hands it to its package installer. */
    const val PATH_WATCH_APK = "/lfly/watch/apk"

    /** Name of the watch APK inside the phone app's assets. */
    const val ASSET_WATCH_APK = "lfly-watch.apk"
}

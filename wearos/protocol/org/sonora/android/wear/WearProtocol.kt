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

    /** Toggle shuffle. */
    const val CMD_SHUFFLE = "shuffle"

    /** Next repeat mode: off, all, one, off. */
    const val CMD_REPEAT = "repeat"

    /** Ask for a fresh [PATH_OUTPUTS]. */
    const val CMD_OUTPUTS = "outputs"

    /** Payload: a [KEY_OUTPUT_ID] from [PATH_OUTPUTS]: play there. */
    const val CMD_SELECT_OUTPUT = "select_output"

    /** Ask for a fresh [PATH_LIBRARY]. */
    const val CMD_LIBRARY = "library"

    /** Payload: a [KEY_COLLECTION_ID]: publish its tracks as [PATH_COLLECTION]. */
    const val CMD_OPEN_COLLECTION = "open_collection"

    /**
     * Payload: "<collection id>\n<index>": play that collection on the phone from that row
     * (index -1 with shuffle on, from a random row).
     */
    const val CMD_PLAY_COLLECTION = "play_collection"

    // ---- Output ids ([KEY_OUTPUT_ID]) ----
    /** This watch as the speaker. */
    const val OUTPUT_WATCH = "watch"

    /** Prefix of the phone's own outputs: "local:<AudioDeviceInfo id>", "local:speaker" for its speaker. */
    const val OUTPUT_LOCAL_PREFIX = "local:"

    /** Prefix of LFly Connect devices: "connect:<device id>". */
    const val OUTPUT_CONNECT_PREFIX = "connect:"

    // ---- Output kinds ([KEY_OUTPUT_KIND]), for the icon ----
    const val KIND_PHONE = "phone"
    const val KIND_EARBUDS = "earbuds"
    const val KIND_HEADPHONES = "headphones"
    const val KIND_SPEAKER = "speaker"
    const val KIND_CAR = "car"
    const val KIND_WATCH = "watch"
    const val KIND_TV = "tv"
    const val KIND_DESKTOP = "desktop"
    const val KIND_TABLET = "tablet"
    const val KIND_CAST = "cast"

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
    const val KEY_ALBUM = "album"
    const val KEY_SHUFFLE = "shuffle"

    /** [REPEAT_OFF], [REPEAT_ALL] or [REPEAT_ONE]. */
    const val KEY_REPEAT = "repeat"
    const val REPEAT_OFF = 0
    const val REPEAT_ALL = 1
    const val REPEAT_ONE = 2

    /** Where the music comes out now (name and kind of the active output), on [PATH_NOW_PLAYING]. */
    const val KEY_OUTPUT_NAME = "output_name"
    const val KEY_OUTPUT_KIND = "output_kind"

    /** True while this watch is the speaker: the crown then turns the watch's own volume. */
    const val KEY_WATCH_SPEAKER = "watch_speaker"

    // ---- PATH_OUTPUTS (phone → watch DataItem) ----
    /** Where the phone can play: its outputs, LFly Connect devices and this watch. */
    const val PATH_OUTPUTS = "/lfly/outputs"

    /** DataMap list, one per output: [KEY_OUTPUT_ID], [KEY_OUTPUT_NAME], [KEY_OUTPUT_KIND], [KEY_OUTPUT_ACTIVE]. */
    const val KEY_OUTPUTS = "outputs"
    const val KEY_OUTPUT_ID = "output_id"
    const val KEY_OUTPUT_ACTIVE = "output_active"

    // ---- PATH_LIBRARY / PATH_COLLECTION (phone → watch DataItems) ----
    /** The phone's library as collections: liked songs, recently played, downloads, playlists. */
    const val PATH_LIBRARY = "/lfly/library"

    /** One opened collection's tracks ([KEY_ITEMS] rows: [KEY_INDEX], [KEY_TITLE], [KEY_ARTIST]). */
    const val PATH_COLLECTION = "/lfly/collection"

    /** DataMap list on [PATH_LIBRARY]: [KEY_COLLECTION_ID], [KEY_TITLE], [KEY_SUBTITLE], [KEY_COLLECTION_KIND]. */
    const val KEY_COLLECTIONS = "collections"
    const val KEY_COLLECTION_ID = "collection_id"
    const val KEY_SUBTITLE = "subtitle"
    const val KEY_COLLECTION_KIND = "collection_kind"

    /** Fixed collection ids; playlists are "playlist:<id>". */
    const val COLLECTION_LIKED = "liked"
    const val COLLECTION_RECENT = "recent"
    const val COLLECTION_DOWNLOADS = "downloads"
    const val COLLECTION_PLAYLIST_PREFIX = "playlist:"

    /** [KEY_COLLECTION_KIND] values, for the icon. */
    const val COLLECTION_KIND_LIKED = "liked"
    const val COLLECTION_KIND_RECENT = "recent"
    const val COLLECTION_KIND_DOWNLOADS = "downloads"
    const val COLLECTION_KIND_PLAYLIST = "playlist"

    /** Rows of a collection sent to the watch at most. */
    const val COLLECTION_MAX_ROWS = 100

    /** Every DataItem the watch asks for carries this (a clock), so asking again always lands. */
    const val KEY_STAMP = "stamp"

    // ---- The watch as the speaker ----
    /*
     * The phone keeps the queue and decides what plays; the watch only plays the track it is told
     * to, reading the audio bytes from the phone over a Data Layer channel (the watch has no
     * internet of its own here).
     *
     *  - Phone → watch messages "[PATH_SPEAKER]/<op>", payload a JSON object (org.json):
     *      [SPEAKER_LOAD]   {id, title, artist, duration, position, play, seq}: play this track
     *                        from [position] ms (the old one stops); [seq] numbers the loads.
     *      [SPEAKER_PLAY], [SPEAKER_PAUSE]: no payload.
     *      [SPEAKER_SEEK]   {position}.
     *      [SPEAKER_STOP]   no payload: the phone takes the music back; release everything.
     *  - Watch → phone message [PATH_SPEAKER_STATUS], payload JSON
     *      {seq, id, state, position, at}: state is one of [SPEAKER_STATE_BUFFERING],
     *      [SPEAKER_STATE_PLAYING], [SPEAKER_STATE_PAUSED], [SPEAKER_STATE_ENDED],
     *      [SPEAKER_STATE_ERROR]; sent on every change and every few seconds while playing.
     *      On "ended" the phone moves its queue on and sends the next [SPEAKER_LOAD].
     *  - Audio: the watch opens a channel to the phone at
     *      "[PATH_STREAM]?id=<Uri-encoded track id>&from=<byte offset>". The phone answers on the
     *      channel's output stream with a header written by DataOutputStream: an int
     *      ([STREAM_OK] / [STREAM_FAILED]), a long (bytes that follow, -1 when unknown), a UTF
     *      string (MIME type, may be empty), then the audio bytes from that offset.
     */
    const val PATH_SPEAKER = "/lfly/speaker"
    const val SPEAKER_LOAD = "load"
    const val SPEAKER_PLAY = "play"
    const val SPEAKER_PAUSE = "pause"
    const val SPEAKER_SEEK = "seek"
    const val SPEAKER_STOP = "stop"

    const val PATH_SPEAKER_STATUS = "/lfly/speaker_status"
    const val SPEAKER_STATE_BUFFERING = "buffering"
    const val SPEAKER_STATE_PLAYING = "playing"
    const val SPEAKER_STATE_PAUSED = "paused"
    const val SPEAKER_STATE_ENDED = "ended"
    const val SPEAKER_STATE_ERROR = "error"

    // JSON keys of the speaker messages.
    const val J_ID = "id"
    const val J_TITLE = "title"
    const val J_ARTIST = "artist"
    const val J_DURATION = "duration"
    const val J_POSITION = "position"
    const val J_PLAY = "play"
    const val J_SEQ = "seq"
    const val J_STATE = "state"
    const val J_AT = "at"

    const val PATH_STREAM = "/lfly/stream"
    const val STREAM_OK = 0
    const val STREAM_FAILED = 1

    // ---- PATH_QUEUE keys ----
    /** DataMap list, one per row: [KEY_TITLE], [KEY_ARTIST], [KEY_INDEX]. */
    const val KEY_ITEMS = "items"
    const val KEY_INDEX = "index"
    const val KEY_CURRENT_INDEX = "current_index"

    /** Side of the cover JPEG on [PATH_NOW_PLAYING]. */
    const val ART_PX = 240

    /**
     * Small covers for list rows ([PATH_QUEUE], [PATH_LIBRARY], [PATH_COLLECTION]): JPEG Assets at
     * the top level of the DataItem under "[ROW_ART_PREFIX]<position in the list>", side [ROW_ART_PX].
     */
    const val ROW_ART_PREFIX = "art_"
    const val ROW_ART_PX = 72

    /** Rows of a collection that get a [ROW_ART_PREFIX] cover; the rest go without. */
    const val ROW_ART_MAX = 40

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

package org.sonora.android.playback.dj

import org.sonora.android.api.Track
import kotlin.random.Random

/** What the DJ says. Pure, so every line can be tested. */
object DjScript {
    /** Languages the DJ speaks; anything else falls back to English. */
    val LANGUAGES = setOf("it", "en", "ro")

    private const val SUBJECT = "{subject}"
    private const val SONG = "{song}"

    private const val TITLE_MAX = 60
    private const val NAME_MAX = 40

    /** The last intro said for each kind, so the same one never comes twice in a row. */
    private val lastIntro = HashMap<DjBlockKind, String>()

    /** The last phrase that named a first song, kept apart so blocks do not all open the same way. */
    private var lastLead: String? = null

    /** The [Random] that [lastIntro] belongs to: a new one means a new session. */
    private var memoryOwner: Random? = null

    /**
     * The line spoken as [block] starts, in [language] ("it", "en" or "ro"). [first] is true for the
     * first block of a session. The same [random] seed always gives the same line.
     *
     * Calls that share one [random] instance count as one session: an intro is never repeated twice
     * in a row for the same kind. A fresh instance starts with a clean memory, so a seed still
     * decides the whole sequence.
     */
    @Synchronized
    fun line(block: DjBlock, language: String, first: Boolean, random: Random = Random.Default): String {
        val lang = languageOf(language)
        val book = BOOKS.getValue(lang)
        if (random !== memoryOwner) {
            memoryOwner = random
            lastIntro.clear()
            lastLead = null
        }

        val track = block.tracks.firstOrNull()
        val rawSubject = block.subject
            ?: track?.artist?.takeIf { block.kind == DjBlockKind.FOLLOWED_ARTIST }
        val subject = rawSubject?.let { cleanName(it, lang, NAME_MAX) }?.takeIf { it.isNotEmpty() }
        val song = track?.let { songText(it, subject, lang) }

        fun usable(template: String) =
            (song != null || SONG !in template) && (subject != null || SUBJECT !in template)

        val intros = book.intros.getValue(block.kind).filter(::usable)
            .ifEmpty { book.generic.filter(::usable) }
        val intro = intros.pick(random, lastIntro[block.kind])
        lastIntro[block.kind] = intro

        val parts = ArrayList<String>(3)
        if (first) parts += book.greetings.pick(random)
        parts += intro
        if (song != null && SONG !in intro) {
            parts += book.leads.pick(random, lastLead).also { lastLead = it }
        }
        return parts.joinToString(" ")
            .replace(SUBJECT, subject.orEmpty())
            .replace(SONG, song.orEmpty())
    }

    /** "it", "en" or "ro" from a tag like "it", "IT" or "ro-RO"; English when unknown. */
    internal fun languageOf(language: String): String {
        val code = language.trim().lowercase().substringBefore('-').substringBefore('_')
        return if (code in LANGUAGES) code else "en"
    }

    /** "Title by Artist", or just the title when the artist is the block's subject. */
    private fun songText(track: Track, subject: String?, lang: String): String? {
        val title = cleanTitle(track.title, track.artist, lang).ifEmpty { return null }
        val artist = cleanName(track.artist, lang, NAME_MAX)
        if (artist.isEmpty() || artist.equals(subject, ignoreCase = true)) return title
        return "$title ${BOOKS.getValue(lang).by} $artist"
    }

    /**
     * A song title the speech engine can read: no "(Official Video)" or "- Remastered 2011", no
     * "Artist - " prefix from video titles, "feat." spoken as a word, no emoji, and not too long.
     */
    internal fun cleanTitle(title: String, artist: String, language: String): String {
        var text = title.trim()
        val prefix = artist.trim()
        if (prefix.isNotEmpty()) {
            val withDash = Regex("^" + Regex.escape(prefix) + """\s+[-–—]\s+""", RegexOption.IGNORE_CASE)
            text = text.replace(withDash, "")
        }
        text = BRACKETED.replace(text) { match ->
            val content = match.groupValues[1].trim()
            when {
                CLUTTER.containsMatchIn(content) -> ""
                FEAT_START.containsMatchIn(content) -> " $content"
                else -> match.value
            }
        }
        while (true) {
            val tail = DASH_TAIL.find(text) ?: break
            if (!CLUTTER.containsMatchIn(tail.groupValues[1])) break
            text = text.substring(0, tail.range.first)
        }
        return cap(speakable(text, languageOf(language)), TITLE_MAX)
    }

    /** An artist or mix name the speech engine can read, at most [max] characters. */
    internal fun cleanName(name: String, language: String, max: Int = NAME_MAX): String =
        cap(speakable(name, languageOf(language)), max)

    /** Spells out "feat." and "&", drops emoji and symbols and tidies the spaces. */
    private fun speakable(text: String, lang: String): String {
        val book = BOOKS.getValue(lang)
        return text
            .replace(FEAT, " ${book.with} ")
            .replace(AMPERSAND, " ${book.and} ")
            .replace(INVISIBLE, "")
            .replace(STRAY, " ")
            .replace(EMPTY_BRACKETS, " ")
            .replace(SPACES, " ")
            .replace(" ,", ",")
            .trim(' ', '-', ',', ':', '|')
    }

    /** Cuts [text] at a word boundary so a huge title does not swallow the line. */
    private fun cap(text: String, max: Int): String {
        if (text.length <= max) return text
        var cut = text.take(max)
        val space = cut.lastIndexOf(' ')
        if (space > max / 2) cut = cut.take(space)
        if (cut.count { it == '(' } > cut.count { it == ')' }) cut = cut.substringBeforeLast('(')
        return cut.trim(' ', '-', ',', ':')
    }

    private fun List<String>.pick(random: Random, avoid: String? = null): String {
        val pool = if (size > 1 && avoid != null) filter { it != avoid } else this
        return pool[random.nextInt(pool.size)]
    }

    private val BRACKETED = Regex("""\s*[(\[{【]([^()\[\]{}【】]*)[)\]}】]""")
    private val DASH_TAIL = Regex("""\s+[-–—|]\s+([^-–—|]*)$""")
    private val CLUTTER = Regex(
        """(?i)\b(official|video|videoclip|audio|lyrics?|visuali[sz]er|remaster(ed)?|hd|hq|4k|explicit|""" +
            """mono|stereo|radio edit|mv|testo|letra|versuri|full album|color coded|ufficiale|oficial)\b"""
    )
    private val FEAT_START = Regex("""(?i)^(feat\.?|ft\.?|featuring|with)\s""")
    private val FEAT = Regex("""(?i)\s*\b(featuring|feat\.?|ft\.?)(?=\s|$)\s*""")
    private val AMPERSAND = Regex("""\s*&\s*""")
    private val INVISIBLE = Regex("[︀-️‍⃣]")
    private val STRAY = Regex("""[^\p{L}\p{M}\p{N}\s.,'’!?:()\-]""")
    private val EMPTY_BRACKETS = Regex("""\(\s*\)""")
    private val SPACES = Regex("""\s+""")

    /** Everything the DJ can say in one language. */
    private class Phrasebook(
        /** Openers for the first block; the block intro follows. */
        val greetings: List<String>,
        val intros: Map<DjBlockKind, List<String>>,
        /** For blocks whose kind needs a subject that is missing. */
        val generic: List<String>,
        /** Names the first song after an intro that does not. */
        val leads: List<String>,
        /** "Title <by> Artist". */
        val by: String,
        /** Spoken for "feat.". */
        val with: String,
        /** Spoken for "&". */
        val and: String
    )

    private val ITALIAN = Phrasebook(
        greetings = listOf(
            "Ciao! Sono il tuo DJ.",
            "Eccomi, sono il tuo DJ.",
            "Ciao, alla consolle ci sono io.",
            "Eccoci qua, sono il tuo DJ."
        ),
        intros = mapOf(
            DjBlockKind.FLOW to listOf(
                "Partiamo da $SONG, uno dei tuoi più ascoltati, e vediamo dove ci porta.",
                "Si comincia da $SONG, che ascolti spesso. Da lì ti porto io.",
                "Metto su $SONG, uno dei tuoi pezzi, e da qui ci muoviamo insieme.",
                "Uno dei tuoi preferiti per aprire: $SONG. Poi lasciati portare."
            ),
            DjBlockKind.TOP_PLAYED to listOf(
                "Partiamo dai tuoi pezzi preferiti, quelli che non ti stanchi mai di ascoltare.",
                "Ecco i brani che ascolti di più.",
                "Un giro tra i tuoi preferiti di sempre.",
                "Questi li conosci a memoria. Via con $SONG."
            ),
            DjBlockKind.FOLLOWED_ARTIST to listOf(
                "Adesso un po' di $SUBJECT, uno degli artisti che segui.",
                "È il momento di $SUBJECT.",
                "Spazio a $SUBJECT, solo per te.",
                "Ti piace $SUBJECT, quindi ecco qualche pezzo."
            ),
            DjBlockKind.DISCOVERY to listOf(
                "Ora qualcosa di nuovo, scelto apposta per i tuoi gusti.",
                "Ti porto a scoprire qualche pezzo che forse non conosci ancora.",
                "Un po' di novità, vicine a quello che ascolti di solito.",
                "Facciamo una piccola scoperta insieme: ecco $SONG."
            ),
            DjBlockKind.THROWBACK to listOf(
                "Torniamo un po' indietro, a pezzi che non senti da un po'.",
                "Un tuffo nel passato: ti ricordi $SONG?",
                "Rispolveriamo qualche vecchio amore.",
                "Era da un po' che non li ascoltavi."
            ),
            DjBlockKind.MIX to listOf(
                "Cambiamo atmosfera con il tuo $SUBJECT.",
                "Prossima tappa: $SUBJECT.",
                "Ora un po' di $SUBJECT, preparato per te."
            )
        ),
        generic = listOf(
            "Ecco un po' di musica scelta per te.",
            "Andiamo avanti con la musica.",
            "Continuiamo con qualcosa che ti piacerà."
        ),
        leads = listOf(
            "Si parte con $SONG.",
            "Apriamo con $SONG.",
            "Cominciamo con $SONG.",
            "Primo pezzo: $SONG."
        ),
        by = "di",
        with = "con",
        and = "e"
    )

    private val ENGLISH = Phrasebook(
        greetings = listOf(
            "Hey, it's your DJ.",
            "Hi there, your DJ here.",
            "Good to have you back. It's your DJ.",
            "Hey! I'm your DJ today."
        ),
        intros = mapOf(
            DjBlockKind.FLOW to listOf(
                "Let's start from $SONG, one you play a lot, and see where it takes us.",
                "Kicking off with $SONG, one of your regulars. I'll take it from there.",
                "One of yours to open: $SONG. Then let the music lead.",
                "Starting with $SONG, a song you keep coming back to, and flowing on from it."
            ),
            DjBlockKind.TOP_PLAYED to listOf(
                "Let's start with your favorites, the ones you keep coming back to.",
                "Here are the songs you play the most.",
                "A little run through your all-time favorites.",
                "You know these by heart, so let's go with $SONG."
            ),
            DjBlockKind.FOLLOWED_ARTIST to listOf(
                "Time for some $SUBJECT, one of the artists you follow.",
                "Let's spend a few songs with $SUBJECT.",
                "Up next, a little set of $SUBJECT.",
                "You follow $SUBJECT, so here's a handful just for you."
            ),
            DjBlockKind.DISCOVERY to listOf(
                "Now something new, picked for your taste.",
                "Let me show you a few songs you might not know yet.",
                "Time for some discoveries, close to what you usually play.",
                "Here's something fresh you might love: $SONG."
            ),
            DjBlockKind.THROWBACK to listOf(
                "Let's go back a bit, to songs you haven't heard in a while.",
                "Time for a throwback. Remember $SONG?",
                "Let's dust off a few old favorites.",
                "It's been a while since you played these."
            ),
            DjBlockKind.MIX to listOf(
                "Switching the mood with your $SUBJECT.",
                "Next stop, $SUBJECT.",
                "Now a little $SUBJECT, made for you."
            )
        ),
        generic = listOf(
            "Here's some music picked just for you.",
            "Let's keep the music going.",
            "More music coming your way."
        ),
        leads = listOf(
            "Starting with $SONG.",
            "First up, $SONG.",
            "Kicking off with $SONG.",
            "Here's $SONG."
        ),
        by = "by",
        with = "with",
        and = "and"
    )

    private val ROMANIAN = Phrasebook(
        greetings = listOf(
            "Salut, sunt DJ-ul tău.",
            "Bună! Aici e DJ-ul tău.",
            "Mă bucur că ești aici. Sunt DJ-ul tău.",
            "Hei, la pupitru sunt eu, DJ-ul tău."
        ),
        intros = mapOf(
            DjBlockKind.FLOW to listOf(
                "Pornim de la $SONG, una dintre piesele tale preferate, și vedem unde ne duce.",
                "Începem cu $SONG, pe care o asculți des. De aici te duc eu.",
                "Una de-a ta pentru început: $SONG. Apoi lasă-te purtat de muzică.",
                "Deschidem cu $SONG, o piesă la care revii mereu, și mergem mai departe de la ea."
            ),
            DjBlockKind.TOP_PLAYED to listOf(
                "Începem cu preferatele tale, piesele la care revii mereu.",
                "Iată melodiile pe care le asculți cel mai des.",
                "O tură prin preferatele tale dintotdeauna.",
                "Pe astea le știi pe de rost. Hai cu $SONG."
            ),
            DjBlockKind.FOLLOWED_ARTIST to listOf(
                "Urmează câteva piese de la $SUBJECT, din artiștii pe care îi urmărești.",
                "E momentul pentru $SUBJECT.",
                "Facem loc pentru $SUBJECT, special pentru tine.",
                "Îți place $SUBJECT, așa că am pregătit câteva piese."
            ),
            DjBlockKind.DISCOVERY to listOf(
                "Acum ceva nou, ales pe gustul tău.",
                "Hai să descoperim câteva piese pe care poate nu le știi încă.",
                "Puțină noutate, aproape de ce asculți de obicei.",
                "Uite ceva proaspăt care s-ar putea să-ți placă: $SONG."
            ),
            DjBlockKind.THROWBACK to listOf(
                "Ne întoarcem puțin în timp, la piese pe care nu le-ai mai ascultat de ceva vreme.",
                "Un pic de nostalgie. Îți amintești de $SONG?",
                "Scoatem din sertar câteva preferate mai vechi.",
                "Nu le-ai mai ascultat de ceva timp."
            ),
            DjBlockKind.MIX to listOf(
                "Schimbăm atmosfera cu $SUBJECT.",
                "Următoarea oprire: $SUBJECT.",
                "Acum puțin $SUBJECT, pregătit pentru tine."
            )
        ),
        generic = listOf(
            "Iată puțină muzică aleasă pentru tine.",
            "Mergem mai departe cu muzica.",
            "Urmează ceva ce sigur o să-ți placă."
        ),
        leads = listOf(
            "Începem cu $SONG.",
            "Pornim cu $SONG.",
            "Deschidem cu $SONG.",
            "Prima piesă: $SONG."
        ),
        by = "de la",
        with = "cu",
        and = "și"
    )

    private val BOOKS = mapOf("it" to ITALIAN, "en" to ENGLISH, "ro" to ROMANIAN)
}

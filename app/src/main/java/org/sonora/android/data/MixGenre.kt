package org.sonora.android.data

/**
 * Locally curated genre seeds for the generated Home mixes.
 *
 * Sonora does not receive a genre tag per track, so mixes are built with a transparent
 * heuristic: a track belongs to a genre when its artist matches one of the seed artists
 * or its title/album contains one of the genre keywords. No network calls involved.
 */
enum class MixGenre(
    val id: String,
    val displayName: String,
    val seedArtists: Set<String>,
    val keywords: Set<String> = emptySet()
) {
    EUROPOP(
        id = "europop",
        displayName = "Europop Mix",
        seedArtists = setOf(
            "inna", "alexandra stan", "edward maya", "akcent", "dj project", "smiley",
            "delia", "andia", "carla s dreams", "irina rimes", "the motans", "3 sud est",
            "deepcentral", "fly project", "morris", "vanotek", "manuel riva",
            "deepside deejays", "alex velea", "andreea balan", "matteo", "beregratuito",
            "ani", "mirah", "randi", "laura stoica", "elena gheorghe", "liviu teodorescu",
            "dodo", "amna", "roxen", "wrs", "emma", "diana cuciureanu", "antonia",
            "alina eremia", "bianca tilici", "gabriella", "doriana", "mario fresh",
            "vunk", "directia 5", "alexandra", "mihail", "stefania", "jolie"
        ),
        keywords = setOf("europop")
    ),
    POP(
        id = "pop",
        displayName = "Pop Mix",
        seedArtists = setOf(
            "taylor swift", "the weeknd", "justin bieber", "halsey", "charlie puth",
            "selena gomez", "dua lipa", "ed sheeran", "ariana grande", "shawn mendes",
            "camila cabello", "post malone", "billie eilish", "olivia rodrigo",
            "harry styles", "miley cyrus", "katy perry", "lady gaga", "rihanna",
            "beyonce", "adele", "bruno mars", "sam smith", "jason derulo", "maroon 5",
            "coldplay", "imagine dragons", "sia", "pink", "kesha", "lauv", "troye sivan"
        )
    ),
    DANCE(
        id = "dance",
        displayName = "Dance Mix",
        seedArtists = setOf(
            "calvin harris", "david guetta", "avicii", "alesso", "martin garrix",
            "tiesto", "kygo", "alan walker", "marshmello", "the chainsmokers", "zedd",
            "meduza", "robin schulz", "lost frequencies", "galantis", "sigala",
            "jax jones", "vinai", "r3hab", "afrojack", "steve aoki", "dimitri vegas",
            "like mike", "hardwell", "nicky romero", "bassjackers", "duke dumont",
            "disclosure", "mahmut orhan", "artbat", "solomun", "purple disco machine"
        ),
        keywords = setOf("dance", "remix", "club", "house", "techno", "edm")
    ),
    MANELE(
        id = "manele",
        displayName = "Manele Mix",
        seedArtists = setOf(
            "florin salam", "nicolae guta", "denisa", "mr juve", "susanu", "play aj",
            "danya", "florin peste", "vali vijelie", "dani printul banatului",
            "tzanca uraganu", "nikolas", "culita", "copilul de aur", "liviu guta",
            "jean de la craiova", "adrian minune", "sorinel", "guta", "petruta",
            "asul", "nelutu de la brasov", "bogdan de la ploiesti", "dan bursuc",
            "denis", "robert dinamo", "marinica"
        ),
        keywords = setOf("manele", "manea")
    ),
    HIPHOP(
        id = "hiphop",
        displayName = "Hip-Hop Mix",
        seedArtists = setOf(
            "g eazy", "drake", "travis scott", "kanye west", "kendrick lamar", "j cole",
            "future", "migos", "21 savage", "lil wayne", "eminem", "50 cent",
            "snoop dogg", "dr dre", "nas", "jay z", "cardi b", "nicki minaj",
            "juice wrld", "a\$ap rocky", "asap rocky", "metro boomin", "playboi carti"
        ),
        keywords = setOf("hip hop", "rap", "trap")
    ),
    ROCK(
        id = "rock",
        displayName = "Rock Mix",
        seedArtists = setOf(
            "queen", "nirvana", "ac dc", "metallica", "guns n roses", "bon jovi",
            "linkin park", "red hot chili peppers", "foo fighters", "green day",
            "the rolling stones", "pink floyd", "led zeppelin", "u2", "radiohead",
            "muse", "the killers", "arctic monkeys", "twenty one pilots"
        ),
        keywords = setOf("rock", "metal", "punk")
    ),
    LATINO(
        id = "latino",
        displayName = "Latino Mix",
        seedArtists = setOf(
            "bad bunny", "j balvin", "maluma", "ozuna", "karol g", "daddy yankee",
            "anuel aa", "rauw alejandro", "farruko", "nicky jam", "shakira",
            "enrique iglesias", "luis fonsi", "becky g", "rosalia", "jhay cortez",
            "sech", "myke towers", "arcangel"
        ),
        keywords = setOf("latino", "reggaeton", "bachata", "salsa")
    ),
    RNB(
        id = "rnb",
        displayName = "R&B Mix",
        seedArtists = setOf(
            "sza", "frank ocean", "usher", "chris brown", "brent faiyaz",
            "daniel caesar", "giveon", "summer walker", "jhene aiko", "alicia keys",
            "john legend", "partynextdoor", "the weeknd"
        ),
        keywords = setOf("r&b", "rnb", "soul")
    )
}

package sonnik.core

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** What a saved clip is. The id goes into the file name, so it must stay stable. */
enum class SoundKind(val id: String, val title: String) {
    SPEECH("speech", "Речь"),
    SNORE("snore", "Храп"),
    COUGH("cough", "Кашель"),
    MOVEMENT("move", "Скрип и шорох"),
    STREET("street", "Улица"),
    OTHER("other", "Другое");

    companion object {
        fun byId(id: String?): SoundKind? = entries.firstOrNull { it.id == id }
    }
}

/** A clip's category plus an optional detail ("door", "dog") with its Russian name. */
data class SoundClass(val kind: SoundKind, val detail: String? = null) {
    val detailTitle: String? get() = detail?.let { SoundLabels.detailTitle(it) }
    val title: String get() = detailTitle?.let { "${kind.title} · $it" } ?: kind.title
}

/**
 * Maps YAMNet (AudioSet) labels to the app's categories and short Russian names.
 * Labels that say nothing about the source ("Silence", "Inside, small room") are ignored.
 */
object SoundLabels {
    private data class Entry(val kind: SoundKind, val key: String?, val ru: String?)

    private val table = HashMap<String, Entry>()
    private val titles = HashMap<String, String>()

    private fun add(kind: SoundKind, key: String?, ru: String?, vararg labels: String) {
        for (l in labels) table[l] = Entry(kind, key, ru)
        if (key != null && ru != null) titles[key] = ru
    }

    val ignored = setOf(
        "Silence", "Inside, small room", "Inside, large room or hall", "Inside, public space", "Noise",
        "Environmental noise", "White noise", "Pink noise", "Static", "Field recording", "Reverberation", "Echo",
        "Sound effect", "Distortion", "Sidetone", "Outside, rural or natural", "Hubbub, speech noise, speech babble",
    )

    init {
        add(SoundKind.SPEECH, null, null, "Speech", "Child speech, kid speaking", "Conversation", "Narration, monologue",
            "Babbling", "Speech synthesizer")
        add(SoundKind.SPEECH, "whisper", "шёпот", "Whispering")
        add(SoundKind.SPEECH, "shout", "крик", "Shout", "Bellow", "Whoop", "Yell", "Screaming", "Children shouting")
        add(SoundKind.SPEECH, "laugh", "смех", "Laughter", "Baby laughter", "Giggle", "Snicker", "Belly laugh", "Chuckle, chortle")
        add(SoundKind.SPEECH, "cry", "плач", "Crying, sobbing", "Baby cry, infant cry", "Whimper", "Wail, moan")
        add(SoundKind.SPEECH, "moan", "стон", "Groan", "Grunt", "Sigh")
        add(SoundKind.SPEECH, "hum", "напевание", "Humming", "Singing")

        add(SoundKind.SNORE, null, null, "Snoring", "Snort")
        add(SoundKind.SNORE, "breath", "дыхание", "Breathing", "Wheeze", "Gasp", "Pant")

        add(SoundKind.COUGH, null, null, "Cough", "Throat clearing")
        add(SoundKind.COUGH, "sneeze", "чих", "Sneeze", "Sniff")
        add(SoundKind.COUGH, "hiccup", "икота", "Hiccup")

        add(SoundKind.MOVEMENT, "creak", "скрип", "Creak", "Squeak", "Squeal")
        add(SoundKind.MOVEMENT, "rustle", "шорох", "Rustle", "Rub", "Scratch", "Scrape", "Crumpling, crinkling", "Flap",
            "Zipper (clothing)", "Shuffle", "Tearing")
        add(SoundKind.MOVEMENT, "steps", "шаги", "Walk, footsteps", "Run")
        add(SoundKind.MOVEMENT, "door", "дверь", "Door", "Sliding door", "Slam", "Cupboard open or close", "Drawer open or close")
        add(SoundKind.MOVEMENT, "knock", "стук", "Knock", "Tap", "Thump, thud", "Thunk", "Bang", "Wood", "Clatter",
            "Bouncing", "Whack, thwack", "Slap, smack")

        add(SoundKind.STREET, "car", "машина", "Vehicle", "Motor vehicle (road)", "Car", "Car passing by", "Truck", "Bus",
            "Traffic noise, roadway noise", "Engine", "Idling", "Accelerating, revving, vroom", "Tire squeal", "Skidding",
            "Air brake", "Reversing beeps", "Outside, urban or manmade", "Light engine (high frequency)",
            "Medium engine (mid frequency)", "Heavy engine (low frequency)", "Engine starting")
        add(SoundKind.STREET, "horn", "сигнал", "Vehicle horn, car horn, honking", "Toot", "Air horn, truck horn", "Car alarm")
        add(SoundKind.STREET, "siren", "сирена", "Siren", "Emergency vehicle", "Police car (siren)", "Ambulance (siren)",
            "Fire engine, fire truck (siren)", "Civil defense siren")
        add(SoundKind.STREET, "moto", "мотоцикл", "Motorcycle")
        add(SoundKind.STREET, "train", "поезд", "Rail transport", "Train", "Train whistle", "Train horn",
            "Railroad car, train wagon", "Train wheels squealing", "Subway, metro, underground")
        add(SoundKind.STREET, "plane", "самолёт", "Aircraft", "Aircraft engine", "Jet engine", "Propeller, airscrew",
            "Helicopter", "Fixed-wing aircraft, airplane")
        add(SoundKind.STREET, "dog", "собака", "Dog", "Bark", "Yip", "Howl", "Bow-wow", "Growling", "Whimper (dog)",
            "Canidae, dogs, wolves")
        add(SoundKind.STREET, "bird", "птицы", "Bird", "Bird vocalization, bird call, bird song", "Chirp, tweet", "Squawk",
            "Pigeon, dove", "Coo", "Crow", "Caw", "Owl", "Hoot", "Bird flight, flapping wings")
        add(SoundKind.STREET, "rain", "дождь", "Rain", "Raindrop", "Rain on surface")
        add(SoundKind.STREET, "wind", "ветер", "Wind", "Rustling leaves", "Wind noise (microphone)")
        add(SoundKind.STREET, "thunder", "гром", "Thunderstorm", "Thunder")
        add(SoundKind.STREET, "voices", "голоса", "Chatter", "Crowd", "Children playing", "Cheering")

        add(SoundKind.OTHER, "cat", "кошка", "Cat", "Purr", "Meow", "Hiss", "Caterwaul")
        add(SoundKind.OTHER, "music", "музыка", "Music", "Song", "Background music", "Pop music", "Radio")
        add(SoundKind.OTHER, "tv", "телевизор", "Television")
        add(SoundKind.OTHER, "phone", "телефон", "Telephone", "Telephone bell ringing", "Ringtone", "Alarm", "Alarm clock",
            "Beep, bleep", "Ping", "Ding", "Buzzer")
        add(SoundKind.OTHER, "clock", "часы", "Clock", "Tick", "Tick-tock")
        add(SoundKind.OTHER, "fan", "вентилятор", "Mechanical fan", "Air conditioning", "Whir", "Hum", "Mains hum")
        add(SoundKind.OTHER, "water", "вода", "Water", "Water tap, faucet", "Sink (filling or washing)", "Toilet flush",
            "Drip", "Pour", "Trickle, dribble", "Liquid", "Splash, splatter", "Bathtub (filling or washing)")
        add(SoundKind.OTHER, "dishes", "посуда", "Dishes, pots, and pans", "Cutlery, silverware", "Chink, clink", "Glass")
        add(SoundKind.OTHER, "insect", "насекомое", "Mosquito", "Fly, housefly", "Insect", "Buzz", "Bee, wasp, etc.", "Cricket")
    }

    fun detailTitle(key: String): String? = titles[key]

    /** Category of a single label; unknown labels are [SoundKind.OTHER] without a detail. */
    fun classOf(label: String): SoundClass? {
        if (label in ignored) return null
        val e = table[label] ?: return SoundClass(SoundKind.OTHER)
        return SoundClass(e.kind, e.key)
    }

    /**
     * Picks a clip's category from per-label scores (the highest score of each label over the
     * clip's windows). Speech wins whenever it is reasonably likely: a phrase over a passing
     * car is still a phrase, and phrases are what the app is for.
     */
    fun classify(scores: Map<String, Float>, speechMin: Float = 0.2f, minScore: Float = 0.1f): SoundClass? {
        val ranked = scores.entries
            .filter { it.key !in ignored }
            .sortedByDescending { it.value }
        val speech = ranked.firstOrNull { table[it.key]?.kind == SoundKind.SPEECH }
        if (speech != null && speech.value >= speechMin) return classOf(speech.key)
        val best = ranked.firstOrNull() ?: return null
        if (best.value < minScore) return null
        return classOf(best.key)
    }
}

/**
 * Rough category from the sound itself, used when the neural classifier is not available
 * (unit tests, an unsupported phone): voiced frames mean speech, long or repeated low-pitched
 * bursts mean snoring, anything else is "other".
 */
object HeuristicClassifier {
    fun classify(audio: FloatArray, sampleRate: Int): SoundClass {
        val frameLen = max(1, sampleRate * 30 / 1000)
        val spectrum = Spectrum(frameLen, sampleRate)
        val n = audio.size / frameLen
        if (n == 0) return SoundClass(SoundKind.OTHER)
        val db = DoubleArray(n)
        val speechRatio = DoubleArray(n)
        val lowRatio = DoubleArray(n)
        val frame = FloatArray(frameLen)
        for (i in 0 until n) {
            audio.copyInto(frame, 0, i * frameLen, (i + 1) * frameLen)
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            db[i] = 20 * log10(max(sqrt(sum / frameLen), 1e-10))
            spectrum.analyse(frame)
            val all = spectrum.energy(60.0, Double.MAX_VALUE)
            speechRatio[i] = if (all > 0) spectrum.energy(300.0, 3400.0) / all else 0.0
            lowRatio[i] = if (all > 0) spectrum.energy(60.0, 400.0) / all else 0.0
        }
        val quiet = db.sorted()[n / 10]
        val frameS = frameLen.toDouble() / sampleRate
        var speechS = 0.0
        var lowS = 0.0
        var bursts = 0
        var run = 0.0
        for (i in 0 until n) {
            val loud = db[i] >= quiet + 10
            if (loud && speechRatio[i] >= 0.45) speechS += frameS
            if (loud && lowRatio[i] >= 0.5) {
                run += frameS
                lowS += frameS
            } else {
                if (run >= 0.3) bursts++
                run = 0.0
            }
        }
        if (run >= 0.3) bursts++
        return when {
            speechS >= 0.25 -> SoundClass(SoundKind.SPEECH)
            // Several low bursts, or one long one: a single snore can end up alone in a clip
            // because the pause between breaths is about as long as the gap that ends a clip.
            bursts >= 2 || lowS >= 0.8 -> SoundClass(SoundKind.SNORE)
            else -> SoundClass(SoundKind.OTHER)
        }
    }
}

/**
 * Final category from the neural classifier and the rule-based one. The network knows far more
 * sounds; the rules only overrule it for snoring, which they recognise by its breathing rhythm
 * even when the network hears just a low rumble.
 */
fun combine(model: SoundClass?, rules: SoundClass): SoundClass = when {
    model == null -> rules
    rules.kind == SoundKind.SNORE && model.kind != SoundKind.SPEECH && model.kind != SoundKind.SNORE -> rules
    else -> model
}

/**
 * Keeps the list readable: every phrase is saved, but snoring and other sounds that go on
 * all night are only sampled (one clip per kind every few minutes, and a cap per night).
 */
class ClipPolicy(
    private val snoreEvery: Duration = Duration.ofMinutes(10),
    private val otherEvery: Duration = Duration.ofMinutes(2),
    private val maxOtherPerNight: Int = 120,
) {
    private val last = HashMap<SoundKind, LocalDateTime>()
    private var others = 0

    fun keep(kind: SoundKind, at: LocalDateTime): Boolean {
        if (kind == SoundKind.SPEECH) return true
        if (kind != SoundKind.SNORE && others >= maxOtherPerNight) return false
        val every = if (kind == SoundKind.SNORE) snoreEvery else otherEvery
        val prev = last[kind]
        if (prev != null && Duration.between(prev, at) < every) return false
        last[kind] = at
        if (kind != SoundKind.SNORE) others++
        return true
    }
}

package gr.gamemap

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** What the voice says: Greek names readable by an English voice, abbreviations spelled out. */
@RunWith(RobolectricTestRunner::class)
class VoiceTest {
    @Test
    fun spoken() {
        assertEquals("Patision", Voice.latin("Πατησίων"))
        assertEquals("Kifisias", Voice.latin("Κηφισίας"))
        Voice.init(org.robolectric.RuntimeEnvironment.getApplication())
        val out = StringBuilder()
        Voice.lang = Phrases.ENGLISH
        for (s in listOf("Turn left onto Λεωφ. Βασιλίσσης Σοφίας", "Turn right onto Αγ. Κωνσταντίνου", "Bear left onto Αιόλου",
            "At the roundabout, take the second exit onto Ευαγγελιστρίας", "Turn right onto Π. Κοκκότα"))
            out.append(s).append("  →  ").append(Voice.spoken(s)).append('\n')
        Voice.lang = Phrases.GREEK
        out.append(Voice.spoken("Στρίψε δεξιά στην Λεωφ. Κηφισίας")).append('\n')
        System.getProperty("previewOut")?.let { File(it).apply { mkdirs() }.resolve("voice.txt").writeText(out.toString()) }
    }
}

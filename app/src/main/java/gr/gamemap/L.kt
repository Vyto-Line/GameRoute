package gr.gamemap

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.util.Locale

/**
 * The app in Greek. Texts stay written in English in the code; when Greek is on, [t] swaps each
 * one for its translation: whole texts from [words], texts with numbers or names from [patterns],
 * then common pieces ("5 min", "arrive 12:30") from [pieces]. The phone screens are translated by
 * walking the views ([translateTree]); the car and the map drawing call [t] directly.
 */
object L {
    /** "auto" follows the phone's language, or "en" / "el". */
    var mode = "auto"
        private set
    @Volatile var greek = false
        private set
    private var ctx: Context? = null

    fun init(c: Context) {
        ctx = c.applicationContext
        mode = c.getSharedPreferences("gamemap", Context.MODE_PRIVATE).getString("uiLang", "auto") ?: "auto"
        apply()
    }

    fun setMode(m: String) {
        mode = m
        ctx?.getSharedPreferences("gamemap", Context.MODE_PRIVATE)?.edit()?.putString("uiLang", m)?.apply()
        apply()
    }

    private fun apply() {
        greek = mode == "el" || (mode == "auto" && Locale.getDefault().language == "el")
    }

    val label get() = when (mode) { "en" -> "English"; "el" -> "Ελληνικά"; else -> if (greek) "Αυτόματα (Ελληνικά)" else "Auto (English)" }

    fun t(s: String): String {
        if (!greek || s.isEmpty()) return s
        words[s]?.let { return it }
        // a value with an arrow or spaces around it ("Kilometres  ›", "You  "): the words inside
        val core = s.trim().removeSuffix("›").removeSuffix("+").trim() // ("Work  +": not set yet)
        if (core.isNotEmpty() && core != s) { val tc = t(core); if (tc != core) return s.replace(core, tc) }
        for ((re, out) in patterns) {
            val m = re.matchEntire(s) ?: continue
            var r = out
            for (i in m.groupValues.indices.reversed()) if (i > 0) r = r.replace("$$i", t(m.groupValues[i]))
            return r
        }
        var r = s
        for ((re, out) in pieces) r = re.replace(r, out)
        return r
    }

    private val doneKey = R.id.tr_done

    /** Translates every text in [v] and below; cheap when nothing changed (each view remembers). */
    fun translateTree(v: View) {
        if (!greek) return
        if (v is TextView) {
            val cur = v.text?.toString().orEmpty()
            if (cur.isNotEmpty() && v.getTag(doneKey) != cur) {
                val tr = t(cur)
                if (tr != cur) v.text = tr
                v.setTag(doneKey, tr)
            }
            if (v is EditText) v.hint?.toString()?.let { h -> val th = t(h); if (th != h) v.hint = th }
            v.contentDescription?.toString()?.let { c -> val tc = t(c); if (tc != c) v.contentDescription = tc }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) translateTree(v.getChildAt(i))
    }

    private val patterns: List<Pair<Regex, String>> = listOf(
        "(\\d+) trips" to "$1 διαδρομές",
        "1 trips" to "1 διαδρομή",
        "Level (\\d+) · (\\d+) XP" to "Επίπεδο $1 · $2 XP",
        "Level (\\d+) · (.+)" to "Επίπεδο $1 · $2",
        "GameRoute build (\\d+)" to "GameRoute έκδοση $1",
        "build (\\d+)" to "έκδοση $1",
        "\\+(\\d+) min traffic" to "+$1 λεπ. κίνηση",
        "Jam in (.+) · \\+(\\d+) min" to "Κίνηση σε $1 · +$2 λεπ.",
        "(\\d+) landmarks to discover nearby" to "$1 ορόσημα για ανακάλυψη κοντά",
        "(\\d+) landmarks discovered(.*)" to "$1 ορόσημα ανακαλύφθηκαν$2",
        "Saved: (.+)" to "Αποθηκεύτηκε: $1",
        "Home saved: (.+)" to "Αποθηκεύτηκε ως Σπίτι: $1",
        "Work saved: (.+)" to "Αποθηκεύτηκε ως Δουλειά: $1",
        "Stop: (.+)   ✕" to "Στάση: $1   ✕",
        "Stop at (.+)\\. Tap to remove" to "Στάση: $1. Πατήστε για αφαίρεση",
        "Remove the stop at (.+)\\?" to "Να αφαιρεθεί η στάση: $1;",
        "On · (\\d+) drivers" to "Ενεργό · $1 οδηγοί",
        "(\\d+) drivers" to "$1 οδηγοί",
        "Convoy · (.+)" to "Κομβόι · $1",
        "👥 Convoy · (\\d+)" to "👥 Κομβόι · $1",
        "➤  Join their route · (.+)" to "➤  Στη διαδρομή τους · $1",
        "➤ Join route · (.+)" to "➤ Στη διαδρομή · $1",
        "➤  Drive to (.+)" to "➤  Πήγαινε στον/στην $1",
        "(.+) chose where the convoy goes" to "$1 διάλεξε πού πάει το κομβόι",
        "(.+) CHOSE WHERE THE CONVOY GOES" to "$1 ΔΙΑΛΕΞΕ ΠΟΥ ΠΑΕΙ ΤΟ ΚΟΜΒΟΪ",
        "(.+): tap to accept or decline" to "$1: πάτα για αποδοχή ή απόρριψη",
        "(.+): tap to confirm or cancel" to "$1: πάτα για επιβεβαίωση ή ακύρωση",
        "(.+) suggests a place" to "$1 προτείνει ένα μέρος",
        "(.+) wants to be friends" to "$1 θέλει να γίνετε φίλοι",
        "Convoy with (.+)" to "Κομβόι με $1",
        "(.+) declined your friend request" to "$1 απέρριψε το αίτημα φιλίας",
        "QR code for the convoy (.+)" to "Κωδικός QR για το κομβόι $1",
        "(\\d+) waiting" to "$1 σε αναμονή",
        "(.+) accepted your friend request" to "$1 αποδέχτηκε το αίτημα φιλίας",
        "(.+) invites you to a convoy" to "$1 σε προσκαλεί σε κομβόι",
        "The road ends (.+) before (.+): the rest is a dirt track or a path" to "Ο δρόμος τελειώνει $1 πριν από: $2. Το υπόλοιπο είναι χωματόδρομος ή μονοπάτι",
        "Route to (.+) resumed" to "Η διαδρομή προς $1 συνεχίζεται",
        "(.+) from you" to "$1 από σένα",
        "🏁 Racing: (.+) · they start without you" to "🏁 Τρέχουν: $1 · ξεκινούν χωρίς εσένα",
        "🏁 Racing: (.+)" to "🏁 Τρέχουν: $1",
        "Faster route · saves (\\d+) min" to "Ταχύτερη διαδρομή · κερδίζεις $1 λεπ.",
        "(.+) reported here" to "$1: αναφέρθηκε εδώ",
        "Via (.+)" to "Μέσω $1",
        "(\\d+) toll stations" to "$1 σταθμοί διοδίων",
        "(\\d+) friends online" to "$1 φίλοι συνδεδεμένοι",
        "(.+) invites you" to "$1 σε προσκαλεί",
        "(\\d+) invites" to "$1 προσκλήσεις",
        "convoy to (.+)" to "κομβόι προς $1",
        "Convoy to (.+)" to "Κομβόι προς $1",
        "(\\d+) min ago" to "πριν $1 λεπτά",
        "(\\d+) h ago" to "πριν $1 ώρ.",
        "QR code for (.+)" to "Κωδικός QR για $1",
        "Send a friend request to (.+)\\?" to "Αίτημα φιλίας στον κωδικό $1;",
        "Invite sent to (.+)" to "Η πρόσκληση στάλθηκε: $1",
        "Remove (.+)\\?" to "Αφαίρεση: $1;",
        "Block (.+)\\?" to "Αποκλεισμός: $1;",
        "(\\d+) friend requests" to "$1 αιτήματα φιλίας",
        "(\\d+) friends, none online" to "$1 φίλοι, κανείς συνδεδεμένος",
        "(\\d+) friends" to "$1 φίλοι",
        "(\\d+) online" to "$1 συνδεδεμένοι",
        "ONLINE · (\\d+)" to "ΣΥΝΔΕΔΕΜΕΝΟΙ · $1",
        "(.+) online" to "$1: συνδεδεμένοι",
        "Add (.+) as a friend" to "Προσθήκη φίλου: $1",
        "Unblock (.+)" to "Άρση αποκλεισμού: $1",
        "Online · (\\d+)" to "Συνδεδεμένοι · $1",
        "(.+) suggests: (.+)" to "$1 προτείνει: $2",
        "Suggested to the convoy: (.+)" to "Προτάθηκε στο κομβόι: $1",
        "Convoy's destination: (.+)" to "Προορισμός κομβόι: $1",
        "(.+) chose: (.+)" to "$1 διάλεξε: $2",
        "Saved as (.+)" to "Αποθηκεύτηκε ως $1",
        "(.+) removed the destination" to "$1 αφαίρεσε τον προορισμό",
        "The others see you as \"(.+)\"  ·  Change name" to "Οι άλλοι σε βλέπουν ως «$1»  ·  Αλλαγή ονόματος",
        "build (\\d+)" to "έκδοση $1",
        "Hold your finger on the map where it is, and save it as \"(.+)\"" to "Κράτα το δάχτυλο στον χάρτη εκεί που είναι, και αποθήκευσέ το ως «$1»",
        "(\\d+) drivers · until you leave" to "$1 οδηγοί · μέχρι να αποχωρήσεις",
        "Navigating to (.+)" to "Πλοήγηση προς $1",
        "(.+) REMOVED THE DESTINATION" to "$1 ΑΦΑΙΡΕΣΕ ΤΟΝ ΠΡΟΟΡΙΣΜΟ",
        "You're driving to (.+): your route stays unless you end it." to "Πηγαίνεις στο $1: η διαδρομή σου μένει, εκτός αν τη σταματήσεις.",
        "In your convoy · (.+)" to "Στο κομβόι σου · $1",
        "✓ Ready · waiting for (.+)" to "✓ Έτοιμος · περιμένουμε: $1",
        "Ready (\\d+)/(\\d+)" to "Έτοιμοι $1/$2",
        "Convoy: (\\d+) drivers" to "Κομβόι: $1 οδηγοί",
        "(\\d+) area(s?) · (.+)" to "$1 περιοχ. · $3",
        "(\\d+) camera(s?) saved on this phone\\. GameRoute warns you before each one\\." to "$1 κάμερες αποθηκευμένες στο κινητό. Το GameRoute σας προειδοποιεί πριν από κάθε μία.",
        "(.+) marked: you'll be warned here" to "$1: σημειώθηκε, θα σας προειδοποιεί εδώ",
        "Thanks! (.+) reported" to "Ευχαριστούμε! Αναφέρθηκε: $1",
        "(.+) is ready \\(you have build (\\d+)\\)\\. Download and install it\\?" to "Η $1 είναι έτοιμη (έχετε την έκδοση $2). Λήψη και εγκατάσταση;",
        "You have the latest version \\(build (\\d+)\\)" to "Έχετε την τελευταία έκδοση ($1)",
        "To (.+)" to "Προς $1",
        "Arrived (.+)" to "Άφιξη $1",
        "Saves (\\d+) min · arrive (.+)" to "Κερδίζετε $1 λεπ. · άφιξη $2",
        "Switch · (\\d+)s" to "Αλλαγή · $1s",
        "(\\d+) routes" to "$1 διαδρομές", "1 route" to "1 διαδρομή",
        "Filters · (\\d+)" to "Φίλτρα · $1",
        "Toll in (.+) · (.+)" to "Διόδια σε $1 · $2",
        "Plug: (.+)" to "Βύσμα: $1",
        "(.+) · live traffic" to "$1 · ζωντανή κίνηση",
    ).map { Regex(it.first) to it.second }

    private val pieces: List<Pair<Regex, String>> = listOf(
        "\\bclear\\b" to "ελεύθερα",
        "\\bchecking traffic\\b" to "έλεγχος κίνησης",
        "^Fuel · " to "Καύσιμα · ",
        "^Café · " to "Καφέ · ",
        "^Food · " to "Φαγητό · ",
        "^Car park · " to "Πάρκινγκ · ",
        "^Pharmacy · " to "Φαρμακείο · ",
        "^EV charger · " to "Φορτιστής · ",
        "\\+(\\d+) min traffic" to "+$1 λεπ. κίνηση",
        "(\\d+) min" to "$1 λεπ.",
        "\\barrive (\\d)" to "άφιξη $1",
        "\\bvia " to "μέσω ",
        "🏠 Home" to "🏠 Σπίτι",
        "💼 Work" to "💼 Δουλειά",
        " found\\b" to " βρέθηκαν",
        "\\bExplorer\\b" to "Εξερευνητής",
        "^you$" to "εσείς",
        "· you$" to "· εσείς",
        "\\bVia " to "Μέσω ",
        " ahead\\b" to " μπροστά",
        " behind\\b" to " πίσω",
        " to go\\b" to " ακόμα",
        "€([0-9.]+) tolls" to "€$1 διόδια",
        "\\btolls\\b" to "διόδια",
        "\\bstopped\\b" to "σταματημένος",
        "\\barrived\\b" to "έφτασε",
        "\\bno signal\\b" to "χωρίς σήμα",
        "\\bnot started\\b" to "δεν ξεκίνησε",
        "area average" to "μέσος όρος περιοχής",
        "area avg\\.?" to "μ.ό. περιοχής",
        "\\bJourney\\b" to "Διαδρομή",
        "\\bjourney\\b" to "διαδρομή",
        "\\btravelled\\b" to "διανύθηκαν",
        "saved dodging traffic" to "κερδήθηκαν αποφεύγοντας την κίνηση",
        "\\bdriving\\b" to "οδήγηση",
        "\\blevel (\\d+)" to "επίπεδο $1",
        "new landmarks?" to "νέα ορόσημα",
    ).map { Regex(it.first) to it.second }

    private val words: Map<String, String> = mapOf(
        // home and search
        "Where to?" to "Πού πάμε;",
        "Type a destination" to "Γράψτε προορισμό",
        "Type a place, address or business" to "Γράψτε μέρος, διεύθυνση ή επιχείρηση",
        "Search" to "Αναζήτηση",
        "Searching…" to "Αναζήτηση…",
        "Searching nearby…" to "Αναζήτηση κοντά…",
        "Nothing found" to "Δεν βρέθηκε τίποτα",
        "Nothing found nearby" to "Δεν βρέθηκε τίποτα κοντά",
        "Search failed: no internet?" to "Η αναζήτηση απέτυχε: υπάρχει ίντερνετ;",
        "Couldn't search: no location or internet" to "Δεν έγινε αναζήτηση: χωρίς τοποθεσία ή ίντερνετ",
        "TomTom search failed" to "Η αναζήτηση TomTom απέτυχε",
        "Recent" to "Πρόσφατα",
        "Saved & recent" to "Αποθηκευμένα & πρόσφατα",
        "Home" to "Σπίτι", "Work" to "Δουλειά", "Fuel" to "Καύσιμα", "Food" to "Φαγητό", "Parking" to "Πάρκινγκ",
        "🏠 Home" to "🏠 Σπίτι", "💼 Work" to "💼 Δουλειά", "⛽ Fuel" to "⛽ Καύσιμα", "🍽 Food" to "🍽 Φαγητό", "🅿 Parking" to "🅿 Πάρκινγκ",
        "Home +" to "Σπίτι +", "Work +" to "Δουλειά +",
        "Search for your home address" to "Αναζητήστε τη διεύθυνση του σπιτιού σας",
        "Search for your work address" to "Αναζητήστε τη διεύθυνση της δουλειάς σας",
        "1 landmark to discover nearby" to "1 ορόσημο για ανακάλυψη κοντά",
        "No new landmarks nearby" to "Κανένα νέο ορόσημο κοντά",
        "Landmarks appear once GPS finds you" to "Τα ορόσημα εμφανίζονται μόλις σας βρει το GPS",
        "Drive or walk around to find landmarks" to "Οδηγήστε ή περπατήστε για να βρείτε ορόσημα",
        "Loading map…" to "Φόρτωση χάρτη…",
        "Waiting for GPS…" to "Αναμονή για GPS…",
        "Waiting for your location" to "Αναμονή για την τοποθεσία σας",
        "Waiting for your location…" to "Αναμονή για την τοποθεσία σας…",
        "KM/H" to "ΧΛΜ/Ω",
        // place card
        "Go" to "Πάμε", "➤  Go" to "➤  Πάμε", "Routes" to "Διαδρομές", "Share" to "Κοινοποίηση", "Save" to "Αποθήκευση", "Saved" to "Αποθηκευμένο",
        "Convoy" to "Κομβόι", "Reviews" to "Κριτικές", "Reviews ›" to "Κριτικές ›",
        "＋  Add stop" to "＋  Στάση", "＋ Add stop" to "＋ Στάση", "Go instead" to "Πάμε εκεί",
        "Dropped pin" to "Καρφίτσα", "DROPPED PIN" to "ΚΑΡΦΙΤΣΑ", "Finding the address…" to "Εύρεση διεύθυνσης…",
        "Removed from saved" to "Αφαιρέθηκε από τα αποθηκευμένα",
        "Share place" to "Κοινοποίηση μέρους",
        "Loading rating…" to "Φόρτωση βαθμολογίας…", "⛽ Prices" to "⛽ Τιμές", "Prices and ratings" to "Τιμές και βαθμολογίες",
        "No app to open Google Maps" to "Δεν υπάρχει εφαρμογή για τους Χάρτες Google",
        "All on Google Maps" to "Όλες στους Χάρτες Google",
        "Car park" to "Πάρκινγκ", "Pharmacy" to "Φαρμακείο", "Café" to "Καφέ", "Supermarket" to "Σούπερ μάρκετ", "Charging" to "Φόρτιση",
        "Street" to "Οδός", "Area" to "Περιοχή",
        // routes
        "Leave later" to "Αργότερα", "Leave now" to "Τώρα", "Start" to "Πάμε",
        "CONVOY" to "ΚΟΜΒΟΪ",
        "Finding routes…" to "Εύρεση διαδρομών…", "No route found" to "Δεν βρέθηκε διαδρομή", "Plotting route…" to "Υπολογισμός διαδρομής…",
        "FASTEST" to "ΤΑΧΥΤΕΡΗ", "Fastest" to "Ταχύτερη", "Route" to "Διαδρομή",
        // driving
        "End" to "Τέλος", "Then" to "Μετά", "Mission complete" to "Αποστολή ολοκληρώθηκε",
        "End navigation?" to "Τέλος πλοήγησης;", "End navigation" to "Τέλος πλοήγησης", "Keep going" to "Συνέχεια",
        "Recenter" to "Επανακέντρωση", "Mute voice" to "Σίγαση φωνής", "More" to "Περισσότερα",
        "While driving" to "Κατά την οδήγηση", "Route overview" to "Επισκόπηση διαδρομής",
        "Traffic on the map" to "Κίνηση στον χάρτη", "Traffic shown" to "Η κίνηση εμφανίζεται", "Traffic hidden" to "Η κίνηση κρύφτηκε",
        "Faster route found" to "Βρέθηκε ταχύτερη διαδρομή", "⚡ Faster route found" to "⚡ Βρέθηκε ταχύτερη διαδρομή",
        "Keep current" to "Κράτησε την τρέχουσα", "Switch" to "Αλλαγή",
        "Jam ahead" to "Κίνηση μπροστά", "Destination reached" to "Φτάσατε στον προορισμό",
        "Share your trip" to "Κοινοποίηση διαδρομής", "Share live location (link)" to "Ζωντανή τοποθεσία (σύνδεσμος)",
        "Send the live link again" to "Ξανά ο ζωντανός σύνδεσμος", "Stop sharing live" to "Διακοπή ζωντανής κοινοποίησης",
        "Send arrival time only" to "Μόνο η ώρα άφιξης", "Live sharing stopped" to "Η ζωντανή κοινοποίηση σταμάτησε",
        "Share arrival time" to "Κοινοποίηση ώρας άφιξης", "Share live location" to "Ζωντανή τοποθεσία",
        "● Live" to "● Ζωντανά", "Remove" to "Αφαίρεση", "Keep" to "Κράτησε", "Remove stop" to "Αφαίρεση στάσης",
        "Add a fuel stop" to "Στάση για καύσιμα", "Fuel nearby" to "Καύσιμα κοντά", "Parking nearby" to "Πάρκινγκ κοντά",
        "🅿 Parking nearby" to "🅿 Πάρκινγκ κοντά", "Finding car parks…" to "Εύρεση πάρκινγκ…", "No car parks found nearby" to "Δεν βρέθηκε πάρκινγκ κοντά",
        "Done" to "Τέλος", "Job done" to "Η δουλειά έγινε", "JOB DONE" to "Η ΔΟΥΛΕΙΑ ΕΓΙΝΕ",
        "Area explored!" to "Η περιοχή εξερευνήθηκε!", "AREA EXPLORED!" to "Η ΠΕΡΙΟΧΗ ΕΞΕΡΕΥΝΗΘΗΚΕ!",
        "Quest complete" to "Η αποστολή ολοκληρώθηκε", "QUEST COMPLETE" to "Η ΑΠΟΣΤΟΛΗ ΟΛΟΚΛΗΡΩΘΗΚΕ",
        "Stage clear!" to "Η πίστα πέρασε!", "STAGE CLEAR!" to "Η ΠΙΣΤΑ ΠΕΡΑΣΕ!",
        "Race finished" to "Τέλος αγώνα", "RACE FINISHED" to "ΤΕΛΟΣ ΑΓΩΝΑ",
        "Journey complete" to "Το ταξίδι ολοκληρώθηκε", "JOURNEY COMPLETE" to "ΤΟ ΤΑΞΙΔΙ ΟΛΟΚΛΗΡΩΘΗΚΕ",
        "MISSION COMPLETE" to "ΑΠΟΣΤΟΛΗ ΟΛΟΚΛΗΡΩΘΗΚΕ",
        "Landmark discovered · +25 XP" to "Ορόσημο ανακαλύφθηκε · +25 XP",
        "Average speed" to "Μέση ταχύτητα",
        // alerts and reports
        "Report" to "Αναφορά", "CAMERA HERE (saved on this phone)" to "ΚΑΜΕΡΑ ΕΔΩ (αποθηκεύεται στο κινητό)",
        "Speed camera" to "Κάμερα ταχύτητας", "Red-light camera" to "Κάμερα φαναριού", "Red light camera" to "Κάμερα φαναριού",
        "Marked cameras stay on this phone and warn you every time you pass." to "Οι κάμερες που σημειώνετε μένουν στο κινητό και σας προειδοποιούν κάθε φορά που περνάτε.",
        "Camera marked" to "Η κάμερα σημειώθηκε", "There's a camera here" to "Υπάρχει κάμερα εδώ",
        "Saved on your phone: you'll be warned next time" to "Αποθηκεύτηκε στο κινητό: θα σας προειδοποιεί την επόμενη φορά",
        "📷  Speed camera here" to "📷  Κάμερα ταχύτητας εδώ", "🚦  Red-light camera here" to "🚦  Κάμερα φαναριού εδώ",
        "Police" to "Αστυνομία", "Accident" to "Ατύχημα", "Hazard" to "Κίνδυνος", "Traffic jam" to "Κίνηση", "Road closed" to "Κλειστός δρόμος",
        "Roadworks" to "Έργα", "Lane closed" to "Κλειστή λωρίδα", "Flooding" to "Πλημμύρα", "Ice" to "Πάγος", "Broken-down vehicle" to "Ακινητοποιημένο όχημα",
        "Average speed check" to "Έλεγχος μέσης ταχύτητας", "Still there?" to "Είναι ακόμα εκεί;", "Is it still there?" to "Είναι ακόμα εκεί;",
        "Still there" to "Ακόμα εκεί", "Not there" to "Δεν είναι εκεί",
        // settings
        "Settings" to "Ρυθμίσεις", "Map" to "Χάρτης", "Navigation" to "Πλοήγηση", "Alerts" to "Ειδοποιήσεις", "Sharing" to "Κοινοποίηση",
        "App" to "Εφαρμογή", "Advanced" to "Για προχωρημένους",
        "Style, light or dark, Android Auto style, places, offline maps" to "Στυλ, φωτεινό ή σκοτεινό, στυλ Android Auto, μέρη, χάρτες εκτός σύνδεσης",
        "Voice, faster routes, tolls and motorways, live traffic" to "Φωνή, ταχύτερες διαδρομές, διόδια και αυτοκινητόδρομοι, ζωντανή κίνηση",
        "Speed and red-light cameras, accidents, cameras you marked" to "Κάμερες ταχύτητας και φαναριών, ατυχήματα, οι κάμερές σας",
        "Your name, convoy, live arrival" to "Το όνομά σας, κομβόι, ζωντανή άφιξη",
        "Trip history, explorer profile, privacy, about, updates" to "Ιστορικό, προφίλ εξερευνητή, απόρρητο, σχετικά, ενημερώσεις",
        "Trip history, explorer profile, privacy, about" to "Ιστορικό, προφίλ εξερευνητή, απόρρητο, σχετικά",
        "Star ratings and reviews, welcome screens" to "Βαθμολογίες και κριτικές, οθόνες καλωσορίσματος",
        "Map style" to "Στυλ χάρτη", "Light / dark" to "Φωτεινό / σκοτεινό", "Light / dark mode" to "Φωτεινό / σκοτεινό",
        "Auto (sunrise / sunset)" to "Αυτόματο (ανατολή / δύση)", "Auto" to "Αυτόματο", "Light" to "Φωτεινό", "Dark" to "Σκοτεινό",
        "Android Auto style" to "Στυλ Android Auto", "Same as phone" to "Ίδιο με το κινητό",
        "Car: dark map when headlights are on" to "Αυτοκίνητο: σκοτεινός χάρτης με αναμμένα φώτα",
        "Places on the map (fuel, parking, food…)" to "Μέρη στον χάρτη (καύσιμα, πάρκινγκ, φαγητό…)",
        "Units" to "Μονάδες", "Kilometres" to "Χιλιόμετρα", "Miles" to "Μίλια",
        "Offline maps" to "Χάρτες εκτός σύνδεσης", "None yet" to "Κανένας ακόμα",
        "Spoken directions" to "Φωνητικές οδηγίες", "Off" to "Ανενεργό", "On" to "Ενεργό",
        "Faster routes" to "Ταχύτερες διαδρομές", "Switch automatically" to "Αυτόματη αλλαγή", "Ask me" to "Να ρωτάει",
        "Ask me (keep my route)" to "Να ρωτάει (κρατά τη διαδρομή μου)", "When a faster route is found" to "Όταν βρεθεί ταχύτερη διαδρομή",
        "Avoid tolls" to "Αποφυγή διοδίων", "Avoid motorways" to "Αποφυγή αυτοκινητοδρόμων", "Live traffic" to "Ζωντανή κίνηση",
        "Speed cameras" to "Κάμερες ταχύτητας", "Red light cameras" to "Κάμερες φαναριών", "Accidents & roadworks" to "Ατυχήματα & έργα",
        "Cameras you marked" to "Κάμερες που σημειώσατε",
        "None yet. While driving, tap ⚠ › Speed camera or Red-light camera to mark one where you are." to
            "Καμία ακόμα. Στην οδήγηση, πατήστε ⚠ › Κάμερα ταχύτητας ή Κάμερα φαναριού για να σημειώσετε μία εκεί που είστε.",
        "Delete all" to "Διαγραφή όλων", "Delete" to "Διαγραφή", "OK" to "OK", "Cancel" to "Άκυρο", "Back" to "Πίσω",
        "Back to settings" to "Πίσω στις ρυθμίσεις",
        "Your name in convoys" to "Το όνομά σας στο κομβόι", "Not set" to "Δεν ορίστηκε", "Not set yet" to "Δεν ορίστηκε ακόμα",
        "Drive together" to "Οδηγήστε μαζί", "Start or join" to "Ξεκίνα ή μπες", "Trip history" to "Ιστορικό διαδρομών", "Explorer profile" to "Προφίλ εξερευνητή",
        "Check for updates" to "Έλεγχος ενημερώσεων", "Privacy policy" to "Πολιτική απορρήτου", "About & map credits" to "Σχετικά & χάρτες",
        "Star ratings & reviews" to "Βαθμολογίες & κριτικές", "Google key set" to "Ορίστηκε κλειδί Google",
        "Off: Reviews opens Google Maps" to "Ανενεργό: οι Κριτικές ανοίγουν τους Χάρτες Google",
        "Auto (phone's language)" to "Αυτόματα (γλώσσα κινητού)",
        "Remove from recent" to "Αφαίρεση από τα πρόσφατα",
        "FUEL NEARBY" to "ΚΑΥΣΙΜΑ ΚΟΝΤΑ", "Filters" to "Φίλτρα", "Cheapest" to "Φθηνότερα", "Nearest" to "Κοντινότερα", "On my route" to "Στη διαδρομή",
        "cheapest" to "φθηνότερα", "nearest" to "κοντινότερα", "on my route" to "στη διαδρομή", "charger" to "φορτιστής",
        "Finding prices…" to "Εύρεση τιμών…", "Fuel filters" to "Φίλτρα καυσίμων", "Reset" to "Επαναφορά",
        "In Greece the price service gives area averages, not each station's own price." to "Στην Ελλάδα η υπηρεσία τιμών δίνει μέσους όρους περιοχής, όχι την τιμή κάθε πρατηρίου.",
        "MY FUELS (PRICES ON THE MAP)" to "ΤΑ ΚΑΥΣΙΜΑ ΜΟΥ (ΤΙΜΕΣ ΣΤΟΝ ΧΑΡΤΗ)", "Unleaded 95" to "Αμόλυβδη 95", "LPG (autogas)" to "Υγραέριο (LPG)",
        "ELECTRIC" to "ΗΛΕΚΤΡΙΚΑ", "Show chargers" to "Φορτιστές", "Fast chargers only (50 kW+)" to "Μόνο ταχυφορτιστές (50 kW+)",
        "BRANDS (NONE TICKED = ALL)" to "ΕΤΑΙΡΕΙΕΣ (ΚΑΜΙΑ = ΟΛΕΣ)", "Show stations" to "Εμφάνιση πρατηρίων",
        "Opening the shared location…" to "Άνοιγμα της τοποθεσίας…", "Shared location" to "Τοποθεσία που στάλθηκε",
        "Couldn't find a place in what was shared" to "Δεν βρέθηκε τοποθεσία σε αυτό που στάλθηκε",
        "Your car" to "Το αυτοκίνητό σας", "Parked" to "Παρκαρισμένο", "🚶  Walk there" to "🚶  Πάμε με τα πόδια", "Forget" to "Ξέχασέ το",
        "Save my parking spot here" to "Αποθήκευση θέσης στάθμευσης εδώ", "Parking spot saved" to "Η θέση στάθμευσης αποθηκεύτηκε",
        "No GPS: estimating" to "Χωρίς GPS: εκτίμηση θέσης",
        "JOIN WITH A CODE" to "ΣΥΜΜΕΤΟΧΗ ΜΕ ΚΩΔΙΚΟ", "Paste an invite link" to "Επικόλληση συνδέσμου πρόσκλησης", "Nothing to paste" to "Δεν υπάρχει κάτι για επικόλληση",
        "To start one, open a place or a route and tap Convoy." to "Για να ξεκινήσετε ένα, ανοίξτε ένα μέρος ή μια διαδρομή και πατήστε Κομβόι.",
        "👥 Convoy" to "👥 Κομβόι",
        "Show the welcome screens again" to "Ξανά οι οθόνες καλωσορίσματος", "Language" to "Γλώσσα",
        "Google Maps Platform API key" to "Κλειδί Google Maps Platform API",
        "Directions will be spoken in English" to "Οι οδηγίες θα ακούγονται στα αγγλικά",
        "Checking for updates…" to "Έλεγχος για ενημερώσεις…", "Update available" to "Υπάρχει ενημέρωση", "Install update" to "Εγκατάσταση",
        "Update" to "Ενημέρωση", "Later" to "Αργότερα", "Downloading update…" to "Λήψη ενημέρωσης…",
        "Download failed: check internet" to "Η λήψη απέτυχε: ελέγξτε το ίντερνετ",
        // style names
        "Free Roam" to "Ελεύθερη Περιπλάνηση", "Might City" to "Πόλη της Ισχύος", "Midnight Run" to "Μεσονύχτια Κούρσα", "Blockworld" to "Κυβόκοσμος", "Realm Map" to "Χάρτης Βασιλείου", "Fire Tower" to "Πυροφυλάκιο", "Catch 'Em Trail" to "Κυνήγι Ορόσημων", "8-Bit" to "8-Bit",
        // trips, offline, explorer
        "Clear history" to "Διαγραφή ιστορικού", "Your finished trips will show here." to "Οι διαδρομές σας θα εμφανίζονται εδώ.",
        "City centre" to "Κέντρο πόλης", "Wider area" to "Ευρύτερη περιοχή",
        "Routes and search still need the internet." to "Οι διαδρομές και η αναζήτηση χρειάζονται ίντερνετ.",
        // convoy
        "Join the convoy" to "Μπες στο κομβόι", "Show QR code" to "Εμφάνιση κωδικού QR", "Show the QR code" to "Εμφάνιση κωδικού QR",
        "For people right next to you" to "Για όσους είναι δίπλα σου", "Send it in a message" to "Στείλ' το σε μήνυμα", "Done" to "Τέλος",
        "Scan with the phone's camera: it opens GameRoute and joins. No app? The convoy opens in the browser." to "Σκανάρετε με την κάμερα του κινητού: ανοίγει το GameRoute και μπαίνετε. Χωρίς την εφαρμογή; Το κομβόι ανοίγει στον browser.",
        "▦ QR" to "▦ QR", "CODE" to "ΚΩΔΙΚΟΣ",
        "Waiting for an answer" to "Αναμονή απάντησης", "✓ Accepted" to "✓ Το αποδέχτηκε", "Declined" to "Απορρίφθηκε",
        "Together" to "Μαζί", "friends online" to "φίλοι συνδεδεμένοι", "FRIENDS ONLINE" to "ΦΙΛΟΙ ΣΥΝΔΕΔΕΜΕΝΟΙ", "👥 Convoy" to "👥 Κομβόι",
        "Friends" to "Φίλοι", "YOUR CODE" to "Ο ΚΩΔΙΚΟΣ ΣΟΥ", "＋  Add a friend" to "＋  Προσθήκη φίλου", "Invites" to "Προσκλήσεις",
        "Join the convoy" to "Μπες στο κομβόι", "Requests" to "Αιτήματα", "wants to add you" to "θέλει να σε προσθέσει",
        "No friends yet. Share your code, or add people you drove with in a convoy." to "Δεν έχεις φίλους ακόμα. Στείλε τον κωδικό σου ή πρόσθεσε όσους οδήγησες μαζί σε κομβόι.",
        "Online" to "Συνδεδεμένος", "Offline" to "Εκτός σύνδεσης", "✓✓ Seen" to "✓✓ Το είδε", "✓ Joined" to "✓ Μπήκε", "✓ Sent" to "✓ Στάλθηκε",
        "👥 Invite" to "👥 Πρόσκληση", "👥 Invite ▴" to "👥 Πρόσκληση ▴", "Requests you sent" to "Αιτήματα που έστειλες", "waiting" to "αναμονή",
        "Your location is never shared with friends: only inside a convoy you join." to "Η τοποθεσία σου δεν κοινοποιείται ποτέ σε φίλους: μόνο μέσα σε κομβόι που μπαίνεις.",
        "Add a friend" to "Προσθήκη φίλου", "Let a friend scan this with their camera, or send them the link." to "Δώσε το σε φίλο να το σκανάρει με την κάμερα, ή στείλε του τον σύνδεσμο.",
        "Send link" to "Αποστολή συνδέσμου", "Or type their code" to "Ή γράψε τον κωδικό του", "OR TYPE THEIR CODE" to "Ή ΓΡΑΨΕ ΤΟΝ ΚΩΔΙΚΟ ΤΟΥ",
        "Add" to "Προσθήκη", "＋ Add" to "＋ Προσθήκη", "You drove with" to "Οδήγησες μαζί με", "YOU DROVE WITH" to "ΟΔΗΓΗΣΕΣ ΜΑΖΙ ΜΕ", "Request sent" to "Το αίτημα στάλθηκε",
        "They get a request and choose Accept or Decline. Nothing is shared until they accept." to "Λαμβάνουν αίτημα και επιλέγουν Αποδοχή ή Απόρριψη. Τίποτα δεν κοινοποιείται πριν το αποδεχτούν.",
        "That's your own code" to "Αυτός είναι ο δικός σου κωδικός", "You're friends already" to "Είστε ήδη φίλοι", "Friend request sent" to "Το αίτημα φιλίας στάλθηκε",
        "That isn't a friend code" to "Αυτό δεν είναι κωδικός φίλου", "Add a friend?" to "Προσθήκη φίλου;", "Send request" to "Αποστολή αιτήματος",
        "They choose Accept or Decline. Your location is never shared with friends." to "Επιλέγουν Αποδοχή ή Απόρριψη. Η τοποθεσία σου δεν κοινοποιείται ποτέ σε φίλους.",
        "👥 Invite to a convoy" to "👥 Πρόσκληση σε κομβόι", "Remove friend" to "Αφαίρεση φίλου", "Block" to "Αποκλεισμός",
        "You can add each other again later." to "Μπορείτε να προστεθείτε ξανά αργότερα.",
        "They're removed, and their requests and invites won't reach you. You can unblock them in Settings › Sharing." to "Αφαιρείται, και τα αιτήματα και οι προσκλήσεις του δεν θα σε φτάνουν. Άρση στις Ρυθμίσεις › Κοινοποίηση.",
        "Friends since" to "Φίλοι από", "You'll leave the convoy you're in." to "Θα βγεις από το κομβόι που είσαι.",
        "Invite friends" to "Πρόσκληση φίλων", "No tolls" to "Χωρίς διόδια", "Next route" to "Επόμενη", "🏁 Race" to "🏁 Αγώνας", "Coordinates" to "Συντεταγμένες", "1. Google Maps › Open by default: turn off \"Open supported links\".\n2. GameRoute › Open by default: turn on \"Open supported links\", tap \"Add link\" and tick the maps addresses.\n\nAndroid lets only one app open these links, which is why Google Maps goes first. Without this, you can still share a link to GameRoute or paste it in the search box." to "1. Google Maps › Άνοιγμα από προεπιλογή: απενεργοποιήστε το \"Άνοιγμα υποστηριζόμενων συνδέσμων\".\n2. GameRoute › Άνοιγμα από προεπιλογή: ενεργοποιήστε το \"Άνοιγμα υποστηριζόμενων συνδέσμων\", πατήστε \"Προσθήκη συνδέσμου\" και επιλέξτε τις διευθύνσεις χαρτών.\n\nΤο Android αφήνει μόνο μία εφαρμογή να ανοίγει αυτούς τους συνδέσμους, γι' αυτό πρώτα το Google Maps. Χωρίς αυτό, μπορείτε πάντα να κοινοποιήσετε έναν σύνδεσμο στο GameRoute ή να τον επικολλήσετε στην αναζήτηση.", "GameRoute › Open by default: turn on \"Open supported links\", tap \"Add link\" and tick the maps addresses.\n\nAndroid lets only one app open these links. Without this, you can still share a link to GameRoute or paste it in the search box." to "GameRoute › Άνοιγμα από προεπιλογή: ενεργοποιήστε το \"Άνοιγμα υποστηριζόμενων συνδέσμων\", πατήστε \"Προσθήκη συνδέσμου\" και επιλέξτε τις διευθύνσεις χαρτών.\n\nΤο Android αφήνει μόνο μία εφαρμογή να ανοίγει αυτούς τους συνδέσμους. Χωρίς αυτό, μπορείτε πάντα να κοινοποιήσετε έναν σύνδεσμο στο GameRoute ή να τον επικολλήσετε στην αναζήτηση.", "Open map links in GameRoute" to "Άνοιγμα συνδέσμων χαρτών στο GameRoute", "GameRoute settings" to "Ρυθμίσεις GameRoute", "Google Maps settings" to "Ρυθμίσεις Google Maps", "Close" to "Κλείσιμο", "📍 SHARING LOCATION" to "📍 ΚΟΙΝΟΠΟΙΗΣΗ ΤΟΠΟΘΕΣΙΑΣ", "sharing your location" to "κοινοποιείς την τοποθεσία σου", "not driving" to "δεν οδηγεί", "📍 You're sharing your location" to "📍 Κοινοποιείς την τοποθεσία σου", "The others see you on their map. You're not in the race and no route starts." to "Οι άλλοι σε βλέπουν στον χάρτη τους. Δεν είσαι στον αγώνα και δεν ξεκινά διαδρομή.", "🏁 Race instead" to "🏁 Αγώνας αντί γι' αυτό", "🚗 Drive along instead" to "🚗 Συνοδεία αντί γι' αυτό", "Stop sharing only: drive with the group" to "Οδήγησε με την ομάδα αντί μόνο για κοινοποίηση", "📍 Not driving? Just share my location" to "📍 Δεν οδηγείς; Απλώς κοινοποίησε την τοποθεσία σου", "racing · ready ✓" to "τρέχεις · έτοιμος ✓", "racing · not ready yet" to "τρέχεις · όχι έτοιμος ακόμα", "racing · not started" to "τρέχεις · δεν ξεκίνησες", "🚗 Drive along" to "🚗 Απλή συνοδεία", "Driving along" to "Συνοδεία", "along" to "συνοδ.", "driving along" to "συνοδεία", "driving along · not started" to "συνοδεία · δεν ξεκίνησε", "Ready, then 3-2-1 with the others" to "Έτοιμος, μετά 3-2-1 με τους άλλους", "Same route, no race. Leave when you like" to "Ίδια διαδρομή, χωρίς αγώνα. Φεύγεις όποτε θες", "Same route, no race" to "Ίδια διαδρομή, χωρίς αγώνα", "Start · no race" to "Έναρξη · χωρίς αγώνα", "Nobody else is racing yet" to "Κανείς άλλος δεν τρέχει ακόμα", "✓ Ready · nobody else is racing yet" to "✓ Έτοιμος · κανείς άλλος δεν τρέχει ακόμα", "selected" to "επιλεγμένο", "Go now or wait for the others: the race doesn't wait for you. Until the 3-2-1 starts you can switch back to Race." to "Ξεκίνα τώρα ή περίμενε τους άλλους: ο αγώνας δεν σε περιμένει. Μέχρι να αρχίσει το 3-2-1 μπορείς να γυρίσεις στον Αγώνα.", "Tap again if you're not ready after all. When every racer is, a 3-2-1 countdown starts on every phone and the route starts by itself." to "Πάτα ξανά αν τελικά δεν είσαι έτοιμος. Όταν είναι όλοι όσοι τρέχουν, ξεκινά αντίστροφη μέτρηση 3-2-1 σε κάθε κινητό και η διαδρομή ξεκινά μόνη της.", "When every racer has pressed it, a 3-2-1 countdown starts on every phone and the route starts by itself." to "Όταν το πατήσουν όλοι όσοι τρέχουν, ξεκινά αντίστροφη μέτρηση 3-2-1 σε κάθε κινητό και η διαδρομή ξεκινά μόνη της.", "Convoy: you drove along (not in the race)" to "Κομβόι: ήσουν συνοδεία (εκτός αγώνα)", "Traffic" to "Κίνηση", "Couldn't send the report" to "Η αναφορά δεν στάλθηκε", "Avoids traffic on your route" to "Αποφεύγει την κίνηση στη διαδρομή σου", "You joined the convoy" to "Μπήκατε στο κομβόι", "That convoy has ended" to "Αυτό το κομβόι έχει τελειώσει", "No friends yet" to "Δεν έχετε φίλους ακόμα", "Add friends on your phone: Together › Friends" to "Προσθέστε φίλους από το κινητό: Μαζί › Φίλοι", "Searching…" to "Αναζήτηση…", "Friends invited you" to "Σας προσκάλεσαν φίλοι", "Not online" to "Εκτός σύνδεσης", "Tap to invite" to "Πατήστε για πρόσκληση", "Share the link" to "Κοινοποίηση συνδέσμου", "For people who aren't friends yet" to "Για όσους δεν είναι ακόμα φίλοι",
        "No friends yet: add some" to "Κανένας φίλος ακόμα: πρόσθεσε", "Nobody is blocked" to "Κανείς δεν είναι αποκλεισμένος", "Blocked" to "Αποκλεισμένοι",
        "Show me as online" to "Να φαίνομαι συνδεδεμένος", "Friends can invite me to convoys" to "Οι φίλοι μπορούν να με καλούν σε κομβόι",
        "My friend code" to "Ο κωδικός φίλου μου", "Make a new friend code" to "Νέος κωδικός φίλου", "Make a new friend code?" to "Νέος κωδικός φίλου;",
        "Your friends stay. Old links and QR codes stop working." to "Οι φίλοι σου μένουν. Οι παλιοί σύνδεσμοι και κωδικοί QR σταματούν να λειτουργούν.",
        "Make a new code" to "Νέος κωδικός", "Add friends" to "Πρόσθεσε φίλους", "1 friend request" to "1 αίτημα φιλίας",
        "Tap to accept or decline" to "Πάτα για αποδοχή ή απόρριψη", "You can invite them to convoys now" to "Μπορείς πλέον να τον καλείς σε κομβόι",
        "A convoy" to "Ένα κομβόι", "Send your friend link" to "Στείλε τον σύνδεσμο φίλου", "latest" to "τελευταία", "just now" to "μόλις τώρα", "convoy" to "κομβόι",
        "INVITES" to "ΠΡΟΣΚΛΗΣΕΙΣ", "REQUESTS" to "ΑΙΤΗΜΑΤΑ", "OFFLINE" to "ΕΚΤΟΣ ΣΥΝΔΕΣΗΣ", "REQUESTS YOU SENT" to "ΑΙΤΗΜΑΤΑ ΠΟΥ ΕΣΤΕΙΛΕΣ",
        "YOUR NAME" to "ΤΟ ΟΝΟΜΑ ΣΑΣ", "Edit" to "Αλλαγή", "Your name" to "Το όνομά σας", "Join" to "Συμμετοχή",
        "Code or invite link" to "Κωδικός ή σύνδεσμος πρόσκλησης",
        "Drive somewhere together: everyone sees the others on the map with their speed and arrival time. Start one from a place or a route, or join with a code." to
            "Οδηγήστε κάπου μαζί: όλοι βλέπουν τους άλλους στον χάρτη με την ταχύτητα και την ώρα άφιξής τους. Ξεκινήστε από ένα μέρος ή μια διαδρομή, ή μπείτε με κωδικό.",
        "Convoy code" to "Κωδικός κομβόι", "Copy" to "Αντιγραφή", "Code copied" to "Ο κωδικός αντιγράφηκε", "🔗 Invite" to "🔗 Πρόσκληση",
        "Invite to the convoy" to "Πρόσκληση στο κομβόι", "Leave" to "Αποχώρηση", "Leave convoy" to "Αποχώρηση από το κομβόι",
        "Leave the convoy? The others will stop seeing you." to "Αποχώρηση από το κομβόι; Οι άλλοι δεν θα σας βλέπουν πια.",
        "Stay" to "Παραμονή", "Nobody else yet: send the code or the invite link." to "Κανείς άλλος ακόμα: στείλτε τον κωδικό ή τον σύνδεσμο.",
        "Connecting to the group…" to "Σύνδεση με την ομάδα…", "That isn't a convoy code or link" to "Αυτό δεν είναι κωδικός ή σύνδεσμος κομβόι",
        "No position from them yet" to "Δεν έχει στείλει θέση ακόμα", "Drive to them now" to "Πήγαινε σε αυτούς τώρα",
        "CODE" to "ΚΩΔΙΚΟΣ", "You" to "Εσύ", "EV charger" to "Φορτιστής ΗΟ", "Off: Reviews opens Google Maps" to "Ανενεργό: οι κριτικές ανοίγουν το Google Maps",
        "Switches at sunrise and sunset" to "Αλλάζει με την ανατολή και τη δύση", "English" to "Αγγλικά",
        "Can't find it?" to "Δεν το βρίσκεις;", "Hold your finger on the map where it is" to "Κράτα το δάχτυλο στον χάρτη, στο σημείο που βρίσκεται",
        "Sharing your location with the convoy" to "Κοινοποίηση θέσης στο κομβόι", "GameRoute: mission active" to "GameRoute: αποστολή σε εξέλιξη",
        "Save place" to "Αποθήκευση μέρους", "Saved place" to "Αποθηκευμένο μέρος", "LABEL (OPTIONAL)" to "ΕΤΙΚΕΤΑ (ΠΡΟΑΙΡΕΤΙΚΑ)",
        "The others are asked to confirm." to "Οι άλλοι καλούνται να επιβεβαιώσουν.",
        "Confirm · go there" to "Επιβεβαίωση · πάμε εκεί", "Confirm" to "Επιβεβαίωση",
        "No XP" to "Χωρίς XP", "trips under 200 m" to "διαδρομές κάτω από 200 m", "under 200 m" to "κάτω από 200 m",
        "e.g. Dad, Gym, Grandma" to "π.χ. Μπαμπάς, Γυμναστήριο, Γιαγιά", "Remove" to "Αφαίρεση", "Cancel" to "Άκυρο", "Save" to "Αποθήκευση",
        "With a label it gets its own button on the home screen and in the car, and typing it in search finds it first." to
            "Με ετικέτα αποκτά δικό του κουμπί στην αρχική οθόνη και στο αυτοκίνητο, και βγαίνει πρώτο όταν την πληκτρολογείς στην αναζήτηση.",
        "Save this place" to "Αποθήκευση μέρους",
        "Change ▾" to "Αλλαγή ▾", "Choose another place" to "Άλλο μέρος", "None" to "Κανένας", "Just share where we all are" to "Μόνο κοινή θέση όλων",
        "No destination" to "Χωρίς προορισμό", "The convoy now just shares where everyone is." to "Το κομβόι τώρα απλώς δείχνει πού είναι ο καθένας.",
        "OK, remove" to "Εντάξει, αφαίρεση", "Keep mine" to "Κράτα τον δικό μου", "OK" to "OK", "➤  Join the convoy's route" to "➤  Στη διαδρομή του κομβόι",
        "Your route will change to it." to "Η διαδρομή σου θα αλλάξει προς τα εκεί.", "Not now" to "Όχι τώρα", "Accept" to "Αποδοχή", "Go there" to "Πάμε εκεί",
        "＋  Create a convoy" to "＋  Νέο κομβόι", "You can choose where to go now or later, together." to "Τον προορισμό τον διαλέγετε τώρα ή αργότερα, μαζί.",
        "Drive together: everyone sees the others on the map with their speed and arrival time." to
            "Οδηγήστε μαζί: ο καθένας βλέπει τους άλλους στον χάρτη, με την ταχύτητα και την ώρα άφιξής τους.",
        "No destination yet" to "Χωρίς προορισμό ακόμα", "📍 Choose" to "📍 Επιλογή", "Where is the convoy going?" to "Πού πάει το κομβόι;",
        "CONVOY DESTINATION" to "ΠΡΟΟΡΙΣΜΟΣ ΚΟΜΒΟΪ", "＋  Stop" to "＋  Στάση", "Takes you to where they are right now." to "Σε πάει εκεί που βρίσκεται αυτή τη στιγμή.",
        "🏁 I'm ready" to "🏁 Είμαι έτοιμος",
        "Change" to "Αλλαγή", "✓ Everyone's ready!" to "✓ Όλοι έτοιμοι!", "Everyone's ready!" to "Όλοι έτοιμοι!",
        "GO!" to "ΠΑΜΕ!", "✓ Ready (tap to undo)" to "✓ Έτοιμος (πάτα για αναίρεση)", "Waiting for " to "Περιμένουμε: ",
        "waiting for " to "περιμένουμε: ", "Start without waiting" to "Ξεκίνα χωρίς αναμονή", "✕ Leave convoy" to "✕ Αποχώρηση",
        "ready ✓" to "έτοιμος ✓", "on your own route" to "σε δική σου διαδρομή", "on their own route" to "σε δική του διαδρομή", "not ready yet" to "όχι έτοιμος ακόμα",
        "When everyone has pressed it, a 3-2-1 countdown starts on every phone and the route starts by itself." to
            "Όταν το πατήσουν όλοι, ξεκινά αντίστροφη μέτρηση 3-2-1 σε κάθε κινητό και η διαδρομή ξεκινά μόνη της.",
        "Tap again if you're not ready after all. When everyone is, a 3-2-1 countdown starts on every phone and the route starts by itself." to
            "Πάτα ξανά αν τελικά δεν είσαι έτοιμος. Όταν είναι όλοι, ξεκινά αντίστροφη μέτρηση 3-2-1 σε κάθε κινητό και η διαδρομή ξεκινά μόνη της.",
        "Positions update every second while moving, end-to-end encrypted: only people with the code can see them." to
            "Οι θέσεις ανανεώνονται κάθε δευτερόλεπτο στην κίνηση, κρυπτογραφημένες από άκρο σε άκρο: μόνο όσοι έχουν τον κωδικό τις βλέπουν.",
        "Drive there as a convoy" to "Πάμε εκεί σαν κομβόι", "Convoy: go" to "Κομβόι: πάμε", "● Convoy" to "● Κομβόι",
        "You" to "Εσείς", "you" to "εσείς", "Driver" to "Οδηγός",
        "Shown next to your arrow on the others' maps." to "Εμφανίζεται δίπλα στο βελάκι σας στους χάρτες των άλλων.",
        // welcome
        "Navigation that looks like your favourite games" to "Πλοήγηση που μοιάζει με τα αγαπημένα σας παιχνίδια",
        "Turn-by-turn directions, live traffic and camera alerts, on a map styled like an open-world game. On your phone and in Android Auto." to
            "Οδηγίες βήμα-βήμα, ζωντανή κίνηση και ειδοποιήσεις για κάμερες, σε χάρτη σαν παιχνίδι ανοιχτού κόσμου. Στο κινητό και στο Android Auto.",
        "GameRoute needs your location" to "Το GameRoute χρειάζεται την τοποθεσία σας",
        "To guide you turn by turn and warn you about cameras, even with the screen off or in Android Auto. Your location stays on your phone; only routes and searches go to the map services." to
            "Για να σας οδηγεί βήμα-βήμα και να σας προειδοποιεί για κάμερες, ακόμα και με κλειστή οθόνη ή στο Android Auto. Η τοποθεσία σας μένει στο κινητό· μόνο οι διαδρομές και οι αναζητήσεις πηγαίνουν στις υπηρεσίες χαρτών.",
        "Pick your look" to "Διαλέξτε στυλ", "PICK YOUR LOOK" to "ΔΙΑΛΕΞΤΕ ΣΤΥΛ",
        "You can change it any time from the map, and give Android Auto a different one." to "Μπορείτε να το αλλάξετε όποτε θέλετε από τον χάρτη, και να βάλετε άλλο στο Android Auto.",
        "Next" to "Επόμενο", "Allow location" to "Άδεια τοποθεσίας", "Not now" to "Όχι τώρα", "Start exploring" to "Ξεκινάμε",
        "2 more in Map style. Light or dark follows the sun." to "Άλλα 2 στο Στυλ χάρτη. Φωτεινό ή σκοτεινό ανάλογα με τον ήλιο.",
        // crash report
        "GameRoute closed unexpectedly" to "Το GameRoute έκλεισε απροσδόκητα",
        "A report of what happened was saved on your phone. Send it so the problem can be fixed?" to
            "Μια αναφορά για το τι έγινε αποθηκεύτηκε στο κινητό σας. Να σταλεί για να διορθωθεί το πρόβλημα;",
        "Send report" to "Αποστολή αναφοράς", "Send the report with…" to "Αποστολή αναφοράς με…",
        // car
        "GameRoute update ready: open the app on your phone" to "Νέα έκδοση GameRoute: ανοίξτε την εφαρμογή στο κινητό",
        "GameRoute: mission active" to "GameRoute: πλοήγηση σε εξέλιξη",
        "Is it still there?" to "Είναι ακόμα εκεί;",
    )
}

package gr.gamemap

/**
 * A panel drawn on the car's map in the style (instead of Android Auto's plain grey screens):
 * a list of rows, a grid of big tiles, or a question with two buttons. Taps come back as "cp:<id>".
 */
class CarPanel(
    val key: String,
    val title: String,
    val rows: List<CarRow> = emptyList(),
    val tiles: List<CarRow> = emptyList(),
    /** The one button top right (e.g. the fuel sort). */
    val head: CarRow? = null,
    /** A line under the rows/tiles. */
    val note: String? = null,
    /** A question over a dimmed map: [message], and the rows as its buttons (the last one red). */
    val dialog: Boolean = false,
    val message: String = "",
    val loading: Boolean = false,
    val empty: String = "",
    /**
     * The menu as a side drawer (mockup 55b): columns of rows from the left edge, full height, the map
     * still showing on the right. A row whose id starts "head:" is a section heading (NEARBY…).
     */
    val columns: List<List<CarRow>>? = null,
)

class CarRow(
    val icon: String,
    val title: String,
    val sub: String? = null,
    /** On the right instead of ›: a value (a price, "On"). */
    val right: String? = null,
    val id: String,
    val highlight: Boolean = false,
    val red: Boolean = false,
    /** A pharmacy on duty: violet outline and line under. */
    val moon: Boolean = false,
    /** Closed now: greyed. */
    val faded: Boolean = false,
)

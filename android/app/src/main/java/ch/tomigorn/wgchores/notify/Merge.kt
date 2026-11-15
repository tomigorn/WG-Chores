package ch.tomigorn.wgchores.notify

/** One done-by-others line; kept in the shown notification so a later check can add to it. */
data class DoneItem(val by: String, val chore: String, val doneOn: String) {
    companion object {
        private const val SEP = '\u001F'

        fun encode(items: List<DoneItem>): ArrayList<String> = items.mapTo(ArrayList()) { "${it.by}$SEP${it.chore}$SEP${it.doneOn}" }

        fun decode(raw: List<String>?): List<DoneItem> = raw.orEmpty().mapNotNull { line ->
            line.split(SEP).takeIf { it.size == 3 }?.let { (by, chore, on) -> DoneItem(by, chore, on) }
        }
    }
}

/** A notification still in the shade is extended, not replaced. */
object NotifyMerge {
    fun due(shown: List<String>, new: List<String>): List<String> = (shown + new).distinct()

    /** The same person, chore and day counts once (a check repeated after a lost acknowledgement). */
    fun done(shown: List<DoneItem>, new: List<DoneItem>): List<DoneItem> = (shown + new).distinct()
}

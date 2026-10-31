package com.ozil.omnivoice

object VoiceText {

    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("\\b(period|full stop)\\b", RegexOption.IGNORE_CASE) to ".",
        Regex("\\bcomma\\b", RegexOption.IGNORE_CASE) to ",",
        Regex("\\bquestion mark\\b", RegexOption.IGNORE_CASE) to "?",
        Regex("\\b(exclamation mark|exclamation point)\\b", RegexOption.IGNORE_CASE) to "!",
        Regex("\\bcolon\\b", RegexOption.IGNORE_CASE) to ":",
        Regex("\\bsemicolon\\b", RegexOption.IGNORE_CASE) to ";",
        Regex("\\b(new line|next line)\\b", RegexOption.IGNORE_CASE) to "\n",
        Regex("\\bnew paragraph\\b", RegexOption.IGNORE_CASE) to "\n\n"
    )

    private val SPACE_BEFORE_PUNCT = Regex("\\s+([.,?!:;])")

    fun format(raw: String, spokenPunctuation: Boolean, capitalizeFirst: Boolean = true): String {
        var out = raw.trim()
        if (out.isEmpty()) return ""
        if (spokenPunctuation) {
            for ((re, rep) in RULES) {
                out = re.replace(out, rep)
            }
            out = SPACE_BEFORE_PUNCT.replace(out, "$1")
        }
        out = out.trim()
        if (out.isEmpty()) return ""
        if (!capitalizeFirst) return out
        return out.substring(0, 1).uppercase() + out.substring(1)
    }
}

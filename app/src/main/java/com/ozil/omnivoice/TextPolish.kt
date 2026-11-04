package com.ozil.omnivoice

/** One recognized piece of speech and the silence (ms) that came before it. */
class Chunk(val text: String, val gapMs: Long)

/**
 * Turns raw recognizer pieces into well-structured text: punctuation, capitals,
 * question marks, paragraphs, common spoken-English fixes. Runs on the phone, no internet.
 */
object TextPolish {

    private const val PARA_GAP_MS = 3000L

    private val FILLERS = Regex("\\b(?:um+|uh+|umm+|uhh+|erm|hmm+)\\b[,.]?\\s*", RegexOption.IGNORE_CASE)

    // Accidental stutters only ("the the", "to to"), never meaningful repeats like "very very".
    private val STUTTER = Regex(
        "\\b(the|a|an|to|of|in|and|is|it|i|we|you|so|but|my|this)(?:\\s+\\1\\b)+",
        RegexOption.IGNORE_CASE
    )

    private val CONTRACTIONS = mapOf(
        "dont" to "don't", "cant" to "can't", "wont" to "won't", "didnt" to "didn't",
        "doesnt" to "doesn't", "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't",
        "werent" to "weren't", "couldnt" to "couldn't", "wouldnt" to "wouldn't",
        "shouldnt" to "shouldn't", "hasnt" to "hasn't", "havent" to "haven't",
        "hadnt" to "hadn't", "im" to "I'm", "ive" to "I've", "youre" to "you're",
        "theyre" to "they're", "thats" to "that's", "whats" to "what's", "theres" to "there's",
        "gonna" to "going to", "wanna" to "want to"
    )
    private val CONTRACTION_RE =
        Regex("\\b(" + CONTRACTIONS.keys.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    private val I_RE = Regex("\\bi\\b")

    private val INTRO = Regex(
        "^(hello|hi|hey|okay|ok|well|yes|no|actually|anyway|basically|firstly|secondly|" +
            "thirdly|finally|honestly|sure|नमस्ते|नमस्कार)\\s+(?=\\S)",
        RegexOption.IGNORE_CASE
    )

    // Comma before a contrasting conjunction, once there are at least 4 words before it.
    private val CONJ_EN = Regex(
        "(\\S+(?:\\s+\\S+){3,}?)(?<![,.?!])\\s+(but|although|though|whereas)\\b",
        RegexOption.IGNORE_CASE
    )
    private val CONJ_INDIC = Regex(
        "(\\S+(?:\\s+\\S+){2,}?)(?<![,.?!।])\\s+(लेकिन|परंतु|मगर|पण)(?=\\s)"
    )

    private val Q_START = setOf(
        "what", "why", "how", "when", "where", "who", "whom", "whose", "which",
        "can", "could", "would", "should", "do", "does", "did", "is", "are", "was",
        "were", "will", "have", "has", "shall", "may"
    )
    private val Q_INDIC = setOf(
        "क्या", "कैसे", "क्यों", "कब", "कहाँ", "कहां", "कौन", "कितना", "कितने", "कितनी",
        "काय", "कसे", "कसा", "केव्हा", "कुठे", "कोण", "किती", "कोणता"
    )

    private val CONTINUES = setOf(
        "and", "but", "so", "because", "which", "that", "or", "then", "also", "with",
        "to", "of", "for", "as", "if", "when", "while", "although", "though",
        "और", "लेकिन", "क्योंकि", "फिर", "तब", "जो", "कि", "पण", "आणि", "कारण", "तर", "म्हणून"
    )

    fun polish(chunks: List<Chunk>, lang: String): String {
        val endMark = if (lang.startsWith("hi")) "।" else "."
        val out = StringBuilder()
        val items = chunks.filter { it.text.isNotBlank() }
        for ((i, c) in items.withIndex()) {
            val cleaned = clean(c.text)
            if (cleaned.isBlank()) continue
            val isLast = i == items.lastIndex
            val nextGap = if (isLast) Long.MAX_VALUE else items[i + 1].gapMs
            val nextFirst = if (isLast) "" else firstWord(items[i + 1].text)
            val t = terminate(cleaned, isLast, nextGap, nextFirst, endMark)
            if (out.isNotEmpty()) {
                out.append(if (c.gapMs >= PARA_GAP_MS) "\n\n" else " ")
            }
            out.append(t)
        }
        return capitalize(tidy(out.toString())).trim()
    }

    private fun firstWord(s: String): String =
        s.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.trim(',', '.', '?', '!') ?: ""

    private fun clean(raw: String): String {
        var t = raw.trim()
        t = FILLERS.replace(t, "")
        t = STUTTER.replace(t, "\$1")
        t = CONTRACTION_RE.replace(t) { CONTRACTIONS[it.value.lowercase()] ?: it.value }
        t = I_RE.replace(t, "I")
        t = INTRO.replace(t) { it.groupValues[1] + ", " }
        t = CONJ_EN.replace(t) { it.groupValues[1] + ", " + it.groupValues[2] }
        t = CONJ_INDIC.replace(t) { it.groupValues[1] + ", " + it.groupValues[2] }
        return t.trim()
    }

    private fun isQuestion(t: String): Boolean {
        val words = t.trim().split(Regex("\\s+"))
        if (words.isEmpty() || words.size > 22) return false
        val first = words[0].lowercase().trim(',', '.')
        if (first in Q_START) return true
        return words.size <= 14 && words.any { it in Q_INDIC }
    }

    private fun terminate(
        text: String,
        isLast: Boolean,
        nextGap: Long,
        nextFirst: String,
        endMark: String
    ): String {
        val t = text.trimEnd()
        if (t.isEmpty()) return t
        if (t.last() in ".?!।…") return t
        val base = t.trimEnd(',', ';', ':')
        if (isQuestion(base)) return "$base?"
        if (isLast || nextGap >= PARA_GAP_MS) return base + endMark
        val wordCount = base.split(Regex("\\s+")).size
        val continues = nextFirst in CONTINUES || wordCount < 4
        return if (continues) "$base," else base + endMark
    }

    private fun tidy(s: String): String {
        var o = s.replace(Regex("[ \\t]+"), " ")
        o = o.replace(Regex(" +([,.;:!?।])"), "\$1")
        o = o.replace(Regex(",\\s*,"), ",")
        o = o.replace(Regex(",\\s*([.?!।])"), "\$1")
        o = o.replace(Regex("([,;:])(?=\\p{L})"), "\$1 ")
        o = o.replace(Regex(" *\\n *"), "\n")
        return o
    }

    private fun capitalize(s: String): String =
        Regex("(^|[.?!।]\\s+|\\n+•?\\s*)(\\p{L})").replace(s) {
            it.groupValues[1] + it.groupValues[2].uppercase()
        }
}

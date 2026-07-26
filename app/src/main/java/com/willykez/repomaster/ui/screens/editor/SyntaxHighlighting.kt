package com.willykez.repomaster.ui.screens.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.willykez.repomaster.ui.theme.SyntaxColorSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Which family of highlighting rules applies to a file, inferred from its
 * extension. This is intentionally coarse — "C-family" covers Kotlin, Java,
 * JS/TS, and friends with one shared rule set, since their comment/string/
 * number syntax is close enough that one tokenizer serves all of them well
 * enough for a mobile code viewer (this isn't trying to be a full IDE).
 */
enum class CodeLanguage {
    KOTLIN_JAVA_C_STYLE, // .kt .kts .java .js .jsx .ts .tsx .c .cpp .h .cs .go .swift .dart
    XML_HTML,            // .xml .html .htm
    MARKDOWN,            // .md .markdown
    JSON,                // .json
    YAML,                // .yml .yaml
    PROPERTIES,          // .properties .gradle.properties .env
    SHELL,               // .sh .bash
    PYTHON,              // .py
    PLAIN,               // everything else — no highlighting, just monospace text
}

fun languageForPath(path: String): CodeLanguage {
    val name = path.substringAfterLast('/').lowercase()
    val ext = name.substringAfterLast('.', missingDelimiterValue = "")
    return when {
        name == "dockerfile" -> CodeLanguage.SHELL
        ext in setOf("kt", "kts", "java", "js", "jsx", "ts", "tsx", "c", "cpp", "cc", "h", "hpp", "cs", "go", "swift", "dart", "gradle") -> CodeLanguage.KOTLIN_JAVA_C_STYLE
        ext in setOf("xml", "html", "htm") -> CodeLanguage.XML_HTML
        ext in setOf("md", "markdown") -> CodeLanguage.MARKDOWN
        ext == "json" -> CodeLanguage.JSON
        ext in setOf("yml", "yaml") -> CodeLanguage.YAML
        ext in setOf("properties", "env", "gitignore", "gitattributes") -> CodeLanguage.PROPERTIES
        ext in setOf("sh", "bash", "zsh") -> CodeLanguage.SHELL
        ext == "py" -> CodeLanguage.PYTHON
        else -> CodeLanguage.PLAIN
    }
}

/** Short label shown in the editor's top bar, e.g. "Kotlin", "XML", "Plain text". */
fun languageLabel(lang: CodeLanguage): String = when (lang) {
    CodeLanguage.KOTLIN_JAVA_C_STYLE -> "Code"
    CodeLanguage.XML_HTML -> "XML / HTML"
    CodeLanguage.MARKDOWN -> "Markdown"
    CodeLanguage.JSON -> "JSON"
    CodeLanguage.YAML -> "YAML"
    CodeLanguage.PROPERTIES -> "Properties"
    CodeLanguage.SHELL -> "Shell"
    CodeLanguage.PYTHON -> "Python"
    CodeLanguage.PLAIN -> "Plain text"
}

private val CStyleKeywords = setOf(
    "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "do",
    "return", "break", "continue", "import", "package", "private", "public", "protected", "internal",
    "override", "open", "abstract", "sealed", "data", "companion", "init", "this", "super", "null",
    "true", "false", "is", "as", "in", "try", "catch", "finally", "throw", "suspend", "inline",
    "const", "lateinit", "by", "get", "set", "typealias", "enum", "annotation", "vararg", "reified",
    "crossinline", "noinline", "operator", "infix", "tailrec", "external", "expect", "actual",
    // Java/JS/C-family extras that don't overlap the Kotlin set above
    "function", "let", "const", "new", "static", "final", "void", "int", "long", "float", "double",
    "boolean", "char", "byte", "short", "extends", "implements", "throws", "instanceof", "export",
    "default", "async", "await", "yield", "switch", "case", "struct", "enum", "namespace", "using",
    "def", "elif", "None", "True", "False", "lambda", "with", "pass", "raise", "except", "self",
)

private val TypeHintPattern = Regex("""\b[A-Z][A-Za-z0-9_]*\b""")
private val NumberPattern = Regex("""\b0[xX][0-9a-fA-F]+\b|\b\d+\.?\d*[fFdDlL]?\b""")
private val AnnotationPattern = Regex("""@[A-Za-z_][A-Za-z0-9_]*""")
private val LineCommentPattern = Regex("""//.*""")
private val BlockCommentPattern = Regex("""/\*[\s\S]*?\*/""")
private val HashCommentPattern = Regex("""#.*""")
private val StringPattern = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'""")
private val TripleStringPattern = Regex("\"\"\"[\\s\\S]*?\"\"\"")
private val XmlTagPattern = Regex("""</?[A-Za-z][A-Za-z0-9_:.\-]*""")
private val XmlAttrPattern = Regex("""\b[A-Za-z_][A-Za-z0-9_:.\-]*(?=\s*=)""")
private val XmlCommentPattern = Regex("""<!--[\s\S]*?-->""")
private val YamlKeyPattern = Regex("""^\s*[A-Za-z0-9_\-.]+(?=\s*:)""", RegexOption.MULTILINE)
private val PropertiesKeyPattern = Regex("""^\s*[A-Za-z0-9_.\-]+(?=\s*[:=])""", RegexOption.MULTILINE)
private val JsonKeyPattern = Regex(""""(?:\\.|[^"\\])*"(?=\s*:)""")

/**
 * A single highlight rule: a regex plus which color to paint every match
 * with. Rules are applied in order and later rules win where they overlap
 * (so e.g. a string containing something that looks like a keyword still
 * renders as a string, as long as STRING comes after KEYWORD in the list —
 * see [rulesFor]).
 */
private data class Rule(val pattern: Regex, val italic: Boolean = false, val colorOf: (SyntaxColorSet) -> androidx.compose.ui.graphics.Color)

private fun rulesFor(lang: CodeLanguage): List<Rule> = when (lang) {
    CodeLanguage.KOTLIN_JAVA_C_STYLE -> listOf(
        Rule(TypeHintPattern) { it.type },
        Rule(NumberPattern) { it.number },
        Rule(AnnotationPattern) { it.annotation },
        Rule(StringPattern) { it.string },
        Rule(TripleStringPattern) { it.string },
        Rule(BlockCommentPattern, italic = true) { it.comment },
        Rule(LineCommentPattern, italic = true) { it.comment },
    )
    CodeLanguage.PYTHON -> listOf(
        Rule(NumberPattern) { it.number },
        Rule(AnnotationPattern) { it.annotation },
        Rule(StringPattern) { it.string },
        Rule(TripleStringPattern) { it.string },
        Rule(HashCommentPattern, italic = true) { it.comment },
    )
    CodeLanguage.SHELL -> listOf(
        Rule(StringPattern) { it.string },
        Rule(HashCommentPattern, italic = true) { it.comment },
    )
    CodeLanguage.XML_HTML -> listOf(
        Rule(StringPattern) { it.string },
        Rule(XmlAttrPattern) { it.attribute },
        Rule(XmlTagPattern) { it.tag },
        Rule(XmlCommentPattern, italic = true) { it.comment },
    )
    CodeLanguage.JSON -> listOf(
        Rule(NumberPattern) { it.number },
        Rule(StringPattern) { it.string },
        Rule(JsonKeyPattern) { it.attribute },
    )
    CodeLanguage.YAML -> listOf(
        Rule(NumberPattern) { it.number },
        Rule(StringPattern) { it.string },
        Rule(HashCommentPattern, italic = true) { it.comment },
        Rule(YamlKeyPattern) { it.attribute },
    )
    CodeLanguage.PROPERTIES -> listOf(
        Rule(HashCommentPattern, italic = true) { it.comment },
        Rule(PropertiesKeyPattern) { it.attribute },
    )
    CodeLanguage.MARKDOWN -> emptyList() // edited as plain monospace text — see MarkdownPreview.kt for the rendered view
    CodeLanguage.PLAIN -> emptyList()
}

/**
 * Builds a highlighted [AnnotatedString] for [text] given [lang]. Keywords
 * (for C-style/Python) are matched separately by a manual word scan, since
 * "match a keyword but not inside a longer identifier" needs word-boundary
 * checks a single shared regex list handles awkwardly.
 */
fun highlightText(text: String, lang: CodeLanguage, colors: SyntaxColorSet): AnnotatedString {
    if (lang == CodeLanguage.PLAIN || lang == CodeLanguage.MARKDOWN || text.isEmpty()) return AnnotatedString(text)

    // Cap highlighting to a sane size — a multi-MB file re-running several
    // regexes on every keystroke would visibly lag typing. Past this size
    // the file still opens and edits fine, just without color.
    if (text.length > 300_000) return AnnotatedString(text)

    return AnnotatedString.Builder(text).apply {
        // Word-boundary keyword pass for languages that have keywords.
        if (lang == CodeLanguage.KOTLIN_JAVA_C_STYLE || lang == CodeLanguage.PYTHON) {
            Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""").findAll(text).forEach { m ->
                if (m.value in CStyleKeywords) {
                    addStyle(SpanStyle(color = colors.keyword), m.range.first, m.range.last + 1)
                }
            }
        }
        rulesFor(lang).forEach { rule ->
            rule.pattern.findAll(text).forEach { m ->
                addStyle(
                    SpanStyle(color = rule.colorOf(colors), fontStyle = if (rule.italic) FontStyle.Italic else FontStyle.Normal),
                    m.range.first, m.range.last + 1,
                )
            }
        }
    }.toAnnotatedString()
}

/**
 * Wraps a debounced, background-computed highlight result as a [VisualTransformation] —
 * see [rememberHighlightedText] for where the actual regex work happens. `filter()` itself
 * does no computation: it just compares the cached result's raw text against what's
 * currently in the field. On a match (the common case once the debounce settles) it returns
 * the cached, styled version; on a mismatch (a keystroke landed since the cache was built)
 * it falls back to plain, unstyled text instantly rather than blocking the keystroke on a
 * fresh regex pass. Offsets are always 1:1 either way, since highlighting never
 * inserts/removes characters.
 */
class CachedHighlightTransformation(private val highlighted: androidx.compose.runtime.State<AnnotatedString>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val cached = highlighted.value
        val result = if (cached.text == text.text) cached else AnnotatedString(text.text)
        return TransformedText(result, OffsetMapping.Identity)
    }
}

/**
 * Runs [highlightText] on a background thread, debounced, rather than synchronously inside
 * [VisualTransformation.filter] on the UI thread. Two things this fixes at once:
 *
 * - **Opening a file no longer stalls the first frame.** The initial value here is plain,
 *   unhighlighted text (returned instantly, since wrapping a string costs nothing) — the
 *   editor renders and becomes scrollable/typeable immediately, and colors "pop in" a beat
 *   later once the background pass finishes. Previously the very first composition blocked
 *   on a full-file regex scan before anything could draw at all.
 * - **Typing in a large file stays smooth.** Every keystroke used to re-run every regex rule
 *   over the entire file, synchronously, before that keystroke could even render. Debouncing
 *   (`delay(120)`, restarted by `produceState` whenever [text] changes) coalesces a burst of
 *   fast typing into one recompute after you pause, instead of one recompute per character.
 */
@Composable
fun rememberHighlightedText(text: String, lang: CodeLanguage, colors: SyntaxColorSet): androidx.compose.runtime.State<AnnotatedString> =
    androidx.compose.runtime.produceState(initialValue = AnnotatedString(text), text, lang, colors) {
        delay(120)
        value = withContext(kotlinx.coroutines.Dispatchers.Default) { highlightText(text, lang, colors) }
    }


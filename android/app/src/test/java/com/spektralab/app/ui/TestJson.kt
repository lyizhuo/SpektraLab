package com.spektralab.app.ui

/**
 * A small JSON reader for this module's captured fixtures.
 *
 * The unit-test source set runs against the mockable `android.jar`, where
 * `org.json.JSONObject` is a stub whose methods throw, and the plan's "no new
 * dependencies" rule rules a JSON library out here too. The two fixtures this
 * reader loads are captured engine replies that this test owns, so a reader
 * that handles JSON's grammar exactly — and nothing more — is smaller than the
 * argument for adding a dependency to the app for it.
 */
internal object TestJson {

    fun resource(path: String): String =
        TestJson::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("missing test resource $path")

    fun parse(text: String): Any? = Parser(text).parseValue()

    private class Parser(private val text: String) {
        private var index = 0

        fun parseValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseKeyword("true", true)
                'f' -> parseKeyword("false", false)
                'n' -> parseKeyword("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                out[key] = parseValue()
                skipWhitespace()
                when (val next = next()) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("expected ',' or '}' at $index but found '$next'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWhitespace()
                when (val next = next()) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("expected ',' or ']' at $index but found '$next'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return builder.toString()
                    '\\' -> when (val escape = next()) {
                        '"', '\\', '/' -> builder.append(escape)
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            val hex = text.substring(index, index + 4)
                            index += 4
                            builder.append(hex.toInt(16).toChar())
                        }
                        else -> error("bad escape '\\$escape'")
                    }
                    else -> builder.append(c)
                }
            }
        }

        private fun parseNumber(): Double {
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] in "-+.eE")) index++
            return text.substring(start, index).toDouble()
        }

        private fun <T> parseKeyword(word: String, value: T): T {
            require(text.startsWith(word, index)) { "expected '$word' at $index" }
            index += word.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun peek(): Char = text[index]

        private fun next(): Char = text[index++]

        private fun expect(expected: Char) {
            val actual = next()
            require(actual == expected) { "expected '$expected' at ${index - 1} but found '$actual'" }
        }
    }
}

internal fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: error("not an object: $this")

internal fun Any?.asList(): List<Any?> = this as? List<Any?> ?: error("not an array: $this")

internal fun Any?.asString(): String = this as? String ?: error("not a string: $this")

internal fun Any?.asDouble(): Double = (this as? Number)?.toDouble() ?: error("not a number: $this")

internal fun Any?.asBool(): Boolean = this as? Boolean ?: error("not a boolean: $this")

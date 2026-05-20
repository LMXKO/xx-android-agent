package com.lmx.xiaoxuanagent.runtime

internal data class SessionPlatformParsedCommand(
    val name: String,
    val args: SessionPlatformCommandArgs,
)

internal class SessionPlatformCommandArgs(
    tokens: List<String>,
) {
    private val positionals = mutableListOf<String>()
    private val options = linkedMapOf<String, String>()
    private val flags = linkedSetOf<String>()

    init {
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (!token.startsWith("--")) {
                positionals += token
                index += 1
                continue
            }
            val body = token.removePrefix("--")
            if (body.isBlank()) {
                index += 1
                continue
            }
            val delimiterIndex = body.indexOf('=')
            if (delimiterIndex >= 0) {
                val key = body.substring(0, delimiterIndex).trim()
                val value = body.substring(delimiterIndex + 1)
                if (key.isNotBlank()) {
                    options[key] = value
                }
                index += 1
                continue
            }
            val next = tokens.getOrNull(index + 1)
            if (next != null && !next.startsWith("--")) {
                options[body] = next
                index += 2
            } else {
                flags += body
                index += 1
            }
        }
    }

    fun positional(index: Int): String = positionals.getOrNull(index).orEmpty()

    fun positionalValues(): List<String> = positionals.toList()

    fun restFrom(index: Int): String =
        positionals.drop(index).joinToString(" ")

    fun option(vararg names: String): String =
        names.firstNotNullOfOrNull { name -> options[name] }?.trim().orEmpty()

    fun flag(vararg names: String): Boolean =
        names.any { it in flags }

    fun hasAnyOptionOrFlag(vararg names: String): Boolean =
        option(*names).isNotBlank() || flag(*names)

    fun hasAnyInput(): Boolean =
        positionals.isNotEmpty() || options.isNotEmpty() || flags.isNotEmpty()

    fun optionOrPositional(
        optionName: String,
        positionalIndex: Int,
    ): String =
        option(optionName).ifBlank { positional(positionalIndex) }

    fun optionOrRest(
        optionName: String,
        positionalIndex: Int,
    ): String =
        option(optionName).ifBlank { restFrom(positionalIndex) }
}

internal object SessionPlatformCommandParser {
    fun parse(
        raw: String,
    ): SessionPlatformParsedCommand? {
        val tokens = tokenize(raw)
        if (tokens.isEmpty()) return null
        val name = tokens.first()
        if (!name.startsWith("/")) return null
        return SessionPlatformParsedCommand(
            name = name,
            args = SessionPlatformCommandArgs(tokens.drop(1)),
        )
    }

    internal fun tokenize(
        raw: String,
    ): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false
        raw.forEach { char ->
            when {
                escaping -> {
                    current.append(char)
                    escaping = false
                }

                char == '\\' -> escaping = true

                quote != null && char == quote -> quote = null

                quote != null -> current.append(char)

                char == '"' || char == '\'' -> quote = char

                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }

                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) {
            tokens += current.toString()
        }
        return tokens
    }
}

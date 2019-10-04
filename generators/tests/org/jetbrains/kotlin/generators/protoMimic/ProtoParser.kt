/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.generators.protoMimic

val Char.validIdSymbol: Boolean
    get() = 'a' <= this && this <= 'z' || 'A' <= this && this <= 'Z' || '0' <= this && this <= '9' || this in "_.@"

val Char.skip: Boolean
    get() = this in " \r\n\t"

fun String.tokenize(): List<String> {
    val result = mutableListOf<String>()

    var idPart: String? = null

    for (c in this.toCharArray()) {
        if (c.validIdSymbol) {
            idPart = (idPart ?: "") + c
        } else {
            if (idPart != null) {
                result += idPart
                idPart = null
            }

            if (!c.skip) {
                result += "$c"
            }
        }
    }

    if (idPart != null) {
        result += idPart
    }

    return result
}

fun String.removeLineComments(): String {
    val i = indexOf("//")
    if (i >= 0) return substring(0, i)

    return this
}

class ProtoParser(val lines: List<String>) {

    var lineIndex = 0
    var tokenIndex = 0

    var tokens: List<String> = emptyList()

    var eof = false

    fun fillTokens() {
        while (tokenIndex >= tokens.size) {
            if (lineIndex >= lines.size) {
                eof = true
                return
            }
            tokens = lines[lineIndex++].removeLineComments().tokenize()
            tokenIndex = 0
        }
    }

    fun nextToken(): String {
        fillTokens()
        if (eof) error("eof")
        return tokens[tokenIndex++]
    }

    private fun raise(token: String, expected: String? = null) {
        var msg = "Unexpected token '$token' and line $lineIndex"
        if (expected != null) {
            msg += "; expected '$expected'"
        }
        error(msg)
    }

    private fun expect(vararg s: String) {
        for (e in s) {
            val token = nextToken()
            if (token != e) raise(token, e)
        }
    }

    private fun expect(s: Collection<String>) {
        for (e in s) {
            val token = nextToken()
            if (token != e) raise(token, e)
        }
    }

    fun parse(): List<Proto> {
        val result = mutableListOf<Proto>()

        while (true) {
            fillTokens()
            if (eof) break
            when (val token = nextToken()) {
                "message" -> result += parseMessage()
                "enum" -> result += parseEnum()
                "syntax" -> expect(" = \"proto2\";".tokenize())
                "package" -> parsePackage()
                "option" -> parseOption()
                "/" -> parseComment()
                else -> raise(token)
            }
        }

        return result
    }

    private fun parsePackage() {
        while (nextToken() != ";") {}
    }

    fun parseMessage(container: String? = null): Proto.Message {
        val name = nextToken().let {
            if (container != null) container + "." + it else it
        }
        expect("{")
        val fields = mutableListOf<MessageEntry>()
        val directives = mutableListOf<String>()
        val nested = mutableListOf<Proto>()
        while (true) {
            when (val token = nextToken()) {
                "}" -> {
                    return Proto.Message(name, fields, directives, nested)
                }
                "message" -> nested += parseMessage(name)
                "enum" -> nested += parseEnum(name)
                "required" -> fields += parseField(FieldKind.REQUIRED)
                "optional" -> fields += parseField(FieldKind.OPTIONAL)
                "repeated" -> fields += parseField(FieldKind.REPEATED)
                "oneof" -> fields += parseOneOf()
                "/" -> directives += parseComment()
                else -> raise(token)
            }
        }
    }

    fun parseOneOf(): MessageEntry.OneOf {
        val name = nextToken()
        expect("{")
        val fields = mutableListOf<MessageEntry.Field>()
        while (true) {
            when (val token = nextToken()) {
                "}" -> {
                    return MessageEntry.OneOf(name, fields)
                }
                "/" -> parseComment()
                else -> {
                    fields += parseField(FieldKind.ONE_OF, token)
                }
            }
        }
    }

    fun parseField(kind: FieldKind, knownType: String? = null): MessageEntry.Field {
        val type = knownType ?: nextToken()
        val name = nextToken()
        expect("=")
        val fieldNumber = nextToken().toInt()

        var defaultValue: String? = null

        val directives = mutableListOf<String>()

        when (val token = nextToken()) {
            ";" -> Unit
            "[" -> {
                expect("default =".tokenize())
                defaultValue = nextToken()
                expect("];".tokenize())
            }
            "/" -> {
                directives += parseComment()
                expect(";")
            }
            else -> raise(token)
        }

        return MessageEntry.Field(kind, name, fieldNumber, type, directives, defaultValue)
    }

    fun parseEnum(container: String? = null): Proto.Enum {
        val name = nextToken()/*.let {
            if (container != null) container + "." + it else it
        }*/
        expect("{")
        val entries = mutableListOf<EnumEntry>()
        val nested = mutableListOf<Proto>()
        while (true) {
            when (val token = nextToken()) {
                "}" -> {
                    return Proto.Enum(name, entries, emptyList(), nested)
                }
                "message" -> nested += parseMessage(name)
                "enum" -> nested += parseEnum(name)
                "/" -> parseComment()
                else -> {
                    val entryName = token
                    expect("=")
                    val index = nextToken().toInt()
                    expect(";")
                    entries += EnumEntry(index, entryName)
                }
            }
        }
    }

    // e.g. `option java_multiple_files = true;`
    fun parseOption() {
        nextToken()
        expect("=")
        val token = nextToken()

        if (token == "\"") {
            while (nextToken() != "\"") {
            }
        }

        expect(";")
    }

    fun parseComment(): List<String> {
        expect("*")
        var token = nextToken()
        var wasStar = false
        val result = mutableListOf<String>()
        while (true) {
            if (wasStar && token == "/") return result

            if (token.startsWith('@')) {
                result += token
            }

            wasStar = token == "*"
            token = nextToken()
        }
        return result
    }
}
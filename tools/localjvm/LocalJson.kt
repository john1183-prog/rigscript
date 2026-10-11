package localjvm

import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor

/** Local-only: a tiny JSON parser plus reflective data-class decoding (ignore unknown keys, defaults for missing keys). */
object LocalJson {
    class Num(val raw: String)

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        check(p.pos == text.length) { "trailing data at ${p.pos}" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        fun expect(word: String) { check(s.startsWith(word, pos)) { "expected $word at $pos" }; pos += word.length }
        fun value(): Any? {
            skipWs()
            return when (s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++; skipWs()
            if (s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs(); val k = str(); skipWs(); check(s[pos] == ':'); pos++
                m[k] = value(); skipWs()
                if (s[pos] == ',') { pos++; continue }
                check(s[pos] == '}') { "bad object at $pos" }; pos++; return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++; skipWs()
            if (s[pos] == ']') { pos++; return l }
            while (true) {
                l += value(); skipWs()
                if (s[pos] == ',') { pos++; continue }
                check(s[pos] == ']') { "bad array at $pos" }; pos++; return l
            }
        }
        fun str(): String {
            check(s[pos] == '"') { "expected string at $pos" }; pos++
            val sb = StringBuilder()
            while (s[pos] != '"') {
                var c = s[pos++]
                if (c == '\\') {
                    c = s[pos++]
                    when (c) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                        else -> sb.append(c)
                    }
                } else sb.append(c)
            }
            pos++
            return sb.toString()
        }
        fun num(): Num {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            check(pos > start) { "bad value at $start" }
            return Num(s.substring(start, pos))
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> decode(cls: KClass<T>, v: Any?): T {
        val map = v as? Map<String, Any?> ?: error("expected an object for ${cls.simpleName}")
        val ctor = cls.primaryConstructor ?: error("no primary constructor: ${cls.simpleName}")
        val args = HashMap<KParameter, Any?>()
        for (p in ctor.parameters) {
            if (map.containsKey(p.name)) args[p] = convert(p.type, map[p.name])
            else if (!p.isOptional) error("missing required ${cls.simpleName}.${p.name}")
        }
        return ctor.callBy(args)
    }

    @Suppress("UNCHECKED_CAST")
    private fun convert(type: KType, v: Any?): Any? {
        if (v == null) { check(type.isMarkedNullable) { "null for non-null $type" }; return null }
        return when (val cls = type.classifier as KClass<*>) {
            Float::class -> (v as Num).raw.toFloat()
            Double::class -> (v as Num).raw.toDouble()
            Int::class -> (v as Num).raw.toInt()
            Long::class -> (v as Num).raw.toLong()
            Boolean::class -> v as Boolean
            String::class -> v as String
            List::class -> (v as List<Any?>).map { convert(type.arguments[0].type!!, it) }
            else -> decode(cls, v)
        }
    }
}

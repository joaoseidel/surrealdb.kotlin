package com.surrealdb.kotlin.query.api.data

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.RecordIdRange
import com.surrealdb.kotlin.core.api.data.Target
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal data class CompiledCondition(
    val surql: String,
    val bindings: Map<String, JsonElement>,
)

internal fun compileCondition(condition: Condition): CompiledCondition =
    ConditionCompiler().apply { appendCondition(condition) }.compile()

private class ConditionCompiler {
    private val text = StringBuilder()
    private val bindings = linkedMapOf<String, JsonElement>()
    private var counter = 0

    fun compile(): CompiledCondition = CompiledCondition(text.toString(), bindings.toMap())

    fun appendCondition(condition: Condition) {
        when (condition) {
            is Comparison -> {
                text.append('(')
                appendExpression(condition.subject)
                text.append(' ').append(condition.op).append(' ')
                appendOperand(condition.operand)
                text.append(')')
            }

            is FieldTest -> {
                text.append('(')
                appendExpression(condition.subject)
                text.append(' ').append(condition.op).append(')')
            }

            is FunctionCall -> {
                text.append(condition.function).append('(')
                appendExpression(condition.subject)
                text.append(", ")
                appendOperand(condition.operand)
                text.append(')')
            }

            is RawCondition -> {
                text.append('(')
                appendFragment(condition.sql, condition.bindings)
                text.append(')')
            }

            is Grouped -> {
                appendCondition(condition.inner)
            }

            is Conjunction -> {
                appendJoined(condition.parts, " AND ")
            }

            is Disjunction -> {
                appendJoined(condition.parts, " OR ")
            }

            is Negation -> {
                text.append('!')
                appendCondition(condition.inner)
            }
        }
    }

    private fun appendJoined(
        parts: List<Condition>,
        joiner: String,
    ) {
        text.append('(')
        parts.forEachIndexed { index, part ->
            if (index > 0) text.append(joiner)
            appendCondition(part)
        }
        text.append(')')
    }

    private fun appendExpression(expression: Expression<*>) {
        when (expression) {
            is Field<*> -> text.append(expression.path.value)
            is Walk<*> -> expression.render({ text.append(it) }, ::appendTarget)
        }
    }

    private fun appendOperand(operand: Any?) {
        when (operand) {
            is Expression<*> -> appendExpression(operand)
            is Target -> appendTarget(operand)
            else -> bind(toJsonElement(operand))
        }
    }

    private fun appendTarget(target: Target) {
        when (target) {
            is Table -> {
                text.append("type::table(")
                bind(JsonPrimitive(target.tableName))
                text.append(')')
            }

            is RecordId -> {
                text.append("type::record(")
                bind(JsonPrimitive(target.table))
                text.append(", ")
                bind(JsonPrimitive(target.id))
                text.append(')')
            }

            is TableRecord<*> -> {
                appendTarget(target.record)
            }

            is Walk<*> -> {
                target.render({ text.append(it) }, ::appendTarget)
            }

            is RecordIdRange -> {
                text.append("type::record(")
                bind(JsonPrimitive(target.table))
                text.append(", ")
                target.start?.let { bind(JsonPrimitive(it)) }
                text.append(if (target.includeEnd) "..=" else "..")
                target.end?.let { bind(JsonPrimitive(it)) }
                text.append(')')
            }

            else -> {
                throw IllegalArgumentException("Unsupported query target: ${target::class.simpleName}")
            }
        }
    }

    private fun appendFragment(
        sql: String,
        values: Map<String, JsonElement>,
    ) {
        val renames = mutableMapOf<String, String>()
        for ((name, value) in values) {
            val target = if (name in bindings) nextParameter() else name
            if (target != name) renames[name] = target
            bindings[target] = value
        }
        var fragment = sql
        if (renames.isNotEmpty()) {
            val pattern =
                Regex(
                    "\\$(" +
                        renames.keys.joinToString("|") { Regex.escape(it) } +
                        ")(?![A-Za-z0-9_])",
                )
            fragment =
                pattern.replace(fragment) { match ->
                    val paramName = match.value.removePrefix("\$")
                    "\$" + renames[paramName]!!
                }
        }
        text.append(fragment)
    }

    private fun bind(value: JsonElement) {
        val name = nextParameter()
        bindings[name] = value
        text.append('$').append(name)
    }

    private fun nextParameter(): String {
        while (true) {
            val candidate = "_${counter++}"
            if (candidate !in bindings) return candidate
        }
    }
}

private fun toJsonElement(value: Any?): JsonElement =
    when (value) {
        null -> {
            JsonNull
        }

        is JsonElement -> {
            value
        }

        is String -> {
            JsonPrimitive(value)
        }

        is Boolean -> {
            JsonPrimitive(value)
        }

        is Number -> {
            JsonPrimitive(value)
        }

        is Collection<*> -> {
            JsonArray(value.map(::toJsonElement))
        }

        else -> {
            throw IllegalArgumentException(
                "Cannot bind '$value' as JSON. Its type is ${value::class.simpleName}; pass a JsonElement or a primitive.",
            )
        }
    }

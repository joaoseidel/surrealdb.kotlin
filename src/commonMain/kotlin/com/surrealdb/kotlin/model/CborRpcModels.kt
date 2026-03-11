package com.surrealdb.kotlin.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
internal data class CborRpcRequest(
    val id: String,
    val method: String,
    val params: List<CborValue> = emptyList(),
)

@Serializable
internal data class CborRpcResponse(
    val id: String? = null,
    val result: CborValue? = null,
    val error: CborRpcError? = null,
)

@Serializable
internal data class CborRpcError(
    val code: Int? = null,
    val message: String,
    val data: CborValue? = null,
)

@Serializable
internal sealed interface CborValue {
    @Serializable
    data object NullValue : CborValue

    @Serializable
    data class BooleanValue(val value: Boolean) : CborValue

    @Serializable
    data class StringValue(val value: String) : CborValue

    @Serializable
    data class LongValue(val value: Long) : CborValue

    @Serializable
    data class DoubleValue(val value: Double) : CborValue

    @Serializable
    data class ArrayValue(val value: List<CborValue>) : CborValue

    @Serializable
    data class ObjectValue(val value: Map<String, CborValue>) : CborValue
}

internal fun JsonElement.toCborValue(): CborValue = when (this) {
    JsonNull -> CborValue.NullValue
    is JsonObject -> CborValue.ObjectValue(entries.associate { (key, value) -> key to value.toCborValue() })
    is JsonArray -> CborValue.ArrayValue(map { it.toCborValue() })
    is JsonPrimitive -> {
        if (isString) {
            CborValue.StringValue(content)
        } else {
            booleanOrNull?.let { return CborValue.BooleanValue(it) }
            longOrNull?.let { return CborValue.LongValue(it) }
            doubleOrNull?.let { return CborValue.DoubleValue(it) }
            CborValue.StringValue(content)
        }
    }
}

internal fun CborValue.toJsonElement(): JsonElement = when (this) {
    CborValue.NullValue -> JsonNull
    is CborValue.BooleanValue -> JsonPrimitive(value)
    is CborValue.StringValue -> JsonPrimitive(value)
    is CborValue.LongValue -> JsonPrimitive(value)
    is CborValue.DoubleValue -> JsonPrimitive(value)
    is CborValue.ArrayValue -> JsonArray(value.map { it.toJsonElement() })
    is CborValue.ObjectValue -> JsonObject(value.mapValues { it.value.toJsonElement() })
}

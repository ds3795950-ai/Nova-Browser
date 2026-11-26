package com.nova.browser

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Structural JSON equality that is available on Android's org.json implementation. */
internal object NovaJsonEquality {
    fun areEqual(left: JSONObject, right: JSONObject): Boolean = valuesEqual(left, right)

    private fun valuesEqual(left: Any?, right: Any?): Boolean {
        if (left === right) return true
        if (left == null || right == null) return false
        if (left === JSONObject.NULL || right === JSONObject.NULL) {
            return left === JSONObject.NULL && right === JSONObject.NULL
        }
        if (left is JSONObject && right is JSONObject) {
            val leftKeys = left.keys().asSequence().toSet()
            val rightKeys = right.keys().asSequence().toSet()
            if (leftKeys != rightKeys) return false
            return leftKeys.all { key -> valuesEqual(left.opt(key), right.opt(key)) }
        }
        if (left is JSONArray && right is JSONArray) {
            if (left.length() != right.length()) return false
            return (0 until left.length()).all { index -> valuesEqual(left.opt(index), right.opt(index)) }
        }
        if (left is Number && right is Number) {
            return runCatching { BigDecimal(left.toString()).compareTo(BigDecimal(right.toString())) == 0 }
                .getOrDefault(left == right)
        }
        return left == right
    }
}

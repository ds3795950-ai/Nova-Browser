package com.nova.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaJsonEqualityTest {
    @Test fun objectKeyOrderDoesNotAffectEquality() {
        val left = JSONObject("{\"profile\":{\"name\":\"Nova\",\"enabled\":true},\"items\":[1,2]}")
        val right = JSONObject("{\"items\":[1,2],\"profile\":{\"enabled\":true,\"name\":\"Nova\"}}")
        assertTrue(NovaJsonEquality.areEqual(left, right))
    }

    @Test fun arrayOrderAndValuesRemainSignificant() {
        assertFalse(NovaJsonEquality.areEqual(JSONObject().put("items", JSONArray("[1,2]")), JSONObject().put("items", JSONArray("[2,1]"))))
        assertFalse(NovaJsonEquality.areEqual(JSONObject().put("value", 1), JSONObject().put("value", 2)))
    }
}

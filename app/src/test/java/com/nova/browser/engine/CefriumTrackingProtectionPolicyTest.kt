package com.nova.browser.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CefriumTrackingProtectionPolicyTest {
    @Test fun blocksKnownCrossSiteTrackersWhenEnabled() {
        assertTrue(CefriumTrackingProtectionPolicy.shouldBlockRequest(
            "https://www.google-analytics.com/collect", "https://news.example.com/article", true
        ))
        assertTrue(CefriumTrackingProtectionPolicy.shouldBlockRequest(
            "https://cdn.segment.io/analytics.js", "https://news.example.com", true
        ))
    }

    @Test fun siblingSubdomainsOfTheSameListedTrackerSiteAreFirstParty() {
        assertFalse(CefriumTrackingProtectionPolicy.shouldBlockRequest(
            "https://cdn.segment.io/analytics.js", "https://app.segment.io", true
        ))
    }

    @Test fun trailingDotTrackerHostsAreCanonicalized() {
        assertTrue(CefriumTrackingProtectionPolicy.shouldBlockRequest(
            "https://google-analytics.com./collect", "https://news.example.com", true
        ))
        assertFalse(CefriumTrackingProtectionPolicy.shouldBlockRequest(
            "https://google-analytics.com/collect", "https://google-analytics.com./", true
        ))
    }

    @Test fun leavesFirstPartyAndUnlistedRequestsAlone() {
        assertFalse(CefriumTrackingProtectionPolicy.shouldBlockRequest(
            "https://analytics.example.com/pixel", "https://example.com", true
        ))
        assertFalse(CefriumTrackingProtectionPolicy.shouldBlockRequest(
            "https://cdn.example.net/app.js", "https://example.com", true
        ))
    }

    @Test fun disabledSiteRuleDisablesTrackerFilter() {
        assertFalse(CefriumTrackingProtectionPolicy.shouldBlockRequest(
            "https://doubleclick.net/ad.js", "https://example.com", false
        ))
    }

    @Test fun profileRequestsWithoutPageContextBlockOnlyKnownTrackersWhenEnabled() {
        assertTrue(CefriumTrackingProtectionPolicy.shouldBlockRequestWithoutTopLevel(
            "https://www.google-analytics.com/collect", true
        ))
        assertFalse(CefriumTrackingProtectionPolicy.shouldBlockRequestWithoutTopLevel(
            "https://cdn.example.net/app.js", true
        ))
        assertFalse(CefriumTrackingProtectionPolicy.shouldBlockRequestWithoutTopLevel(
            "https://www.google-analytics.com/collect", false
        ))
    }

    @Test fun imagePolicyBlocksOnlyImagesForDataSaverOrSiteRule() {
        assertTrue(CefriumTrackingProtectionPolicy.shouldBlockImage(true, true, true))
        assertTrue(CefriumTrackingProtectionPolicy.shouldBlockImage(true, false, false))
        assertFalse(CefriumTrackingProtectionPolicy.shouldBlockImage(false, true, true))
        assertFalse(CefriumTrackingProtectionPolicy.shouldBlockImage(true, false, true))
    }

    @Test fun malformedUrlsAreNeverTreatedAsTrackers() {
        assertFalse(CefriumTrackingProtectionPolicy.shouldBlockRequest(
            "javascript:alert(1)", "https://example.com", true
        ))
    }
}

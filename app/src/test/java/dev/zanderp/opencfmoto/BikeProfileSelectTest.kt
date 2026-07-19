package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke-checks for the 1.0.11 profile registry: Nk800 claims known QR modelIds and returns
 * false for CFDL26 ids; 1000 MT-X stays non-touch (1.0.9). Full CLIENT_INFO scoring is exercised
 * on-device (host JVM lacks a real android.org.json).
 */
class BikeProfileSelectTest {

    @Test
    fun nk800MatchesKnownModelIdsOnly() {
        assertTrue(Nk800Profile.matchesModelId("66660703"))
        assertTrue(Nk800Profile.matchesModelId("66660721"))
        assertTrue(Nk800Profile.matchesModelId("66660732"))
        assertFalse(Nk800Profile.matchesModelId("37426"))
        assertFalse(Nk800Profile.matchesModelId("37416"))
    }

    @Test
    fun selectByModelIdPrefersNk800ForItsIds() {
        assertEquals(Nk800Profile, BikeProfiles.selectByModelId("66660703"))
        // 37426 matches both CFDL26 landscape and portrait — firstOrNull in selectByModelId
        // returns landscape (list order after Nk800).
        assertEquals(Cfdl26LandscapeProfile, BikeProfiles.selectByModelId("37426"))
    }

    @Test
    fun portraitMtxStaysNonTouch() {
        assertFalse(Cfdl26PortraitProfile.supportsScreenTouch)
        assertTrue(Cfdl26LandscapeProfile.supportsScreenTouch)
        assertFalse(Nk800Profile.supportsScreenTouch)
    }

    @Test
    fun buttonDefaultsMatchHandlebarLayout() {
        assertEquals(ButtonAction.KNOB_BACK, ButtonGesture.NAV_BACK.default)
        assertEquals(ButtonAction.KNOB_FORWARD, ButtonGesture.NAV_FWD.default)
        assertEquals(ButtonAction.SELECT, ButtonGesture.SELECT_PRESS.default)
        assertEquals(ButtonAction.DPAD_LEFT, ButtonGesture.NAV_BACK_DOUBLE.default)
        assertEquals(ButtonAction.DPAD_RIGHT, ButtonGesture.NAV_FWD_DOUBLE.default)
        assertEquals(ButtonAction.BACK, ButtonGesture.SELECT_DOUBLE.default)
        assertEquals(ButtonAction.ASSISTANT, ButtonGesture.SELECT_LONG.default)
    }
}

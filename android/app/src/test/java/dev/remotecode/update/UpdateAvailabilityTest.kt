package dev.remotecode.update

import dev.remotecode.data.AppUpdateInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAvailabilityTest {
    private val installed = "a".repeat(64)
    private val next = AppUpdateInfo(available = true, sha256 = "b".repeat(64), size = 100, versionCode = 45)

    @Test fun sameVersionRebuildIsAvailableWhileMatchingBytesAreNot() {
        assertTrue(updateAvailable(next, installed, 45))
        assertFalse(updateAvailable(next.copy(sha256 = installed.uppercase()), installed, 45))
    }

    @Test fun olderBuildIsNotOfferedAndLegacyServerWithoutVersionCodeStillWorks() {
        assertFalse(updateAvailable(next, installed, 46))
        assertTrue(updateAvailable(next.copy(versionCode = null), installed, 46))
    }

    @Test fun missingOrIncompleteArtifactIsNotOffered() {
        assertFalse(updateAvailable(next.copy(available = false), installed, 45))
        assertFalse(updateAvailable(next.copy(sha256 = null), installed, 45))
        assertFalse(updateAvailable(next.copy(sha256 = "bad"), installed, 45))
        assertFalse(updateAvailable(next.copy(size = 0), installed, 45))
    }
}

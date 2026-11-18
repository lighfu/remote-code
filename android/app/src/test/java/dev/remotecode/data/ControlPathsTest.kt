package dev.remotecode.data

import dev.remotecode.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlPathsTest {
    @Test
    fun modeSpecificRoutesDoNotLeakAcrossModesWhileSharedAuthRemainsVisible() {
        val provider = ControlProvider(routes = listOf(
            ControlRoute(id = "app-operation", modeId = "app", purpose = "operate"),
            ControlRoute(id = "desktop-operation", modeId = "desktop", purpose = "operate"),
            ControlRoute(id = "shared-auth", purpose = "auth"),
            ControlRoute(id = "desktop-auth", modeId = "desktop", purpose = "auth"),
        ))
        assertEquals(listOf("app-operation"), provider.routesFor("app", "operate").map { it.id })
        assertEquals(listOf("shared-auth"), provider.routesFor("app", "auth").map { it.id })
        assertEquals(listOf("shared-auth", "desktop-auth"), provider.routesFor("desktop", "auth").map { it.id })
    }

    @Test
    fun refreshedCatalogKeepsValidSelectionAndFallsBackWhenModeDisappears() {
        val snapshot = ControlPathsSnapshot(providers = listOf(
            ControlProvider(id = "first", modes = listOf(ControlMode(id = "app"))),
            ControlProvider(id = "second", modes = listOf(ControlMode(id = "app"), ControlMode(id = "desktop"))),
        ))
        assertEquals(ControlPathsSelection("second", "desktop"), snapshot.resolveSelection("second", "desktop"))
        assertEquals(ControlPathsSelection("second", "app"), snapshot.resolveSelection("second", "removed"))
        assertEquals(ControlPathsSelection("first", "app"), snapshot.resolveSelection("removed", "desktop"))
        assertNull(ControlPathsSnapshot().resolveSelection(null, null))
    }

    @Test
    fun optionalAndFutureServerFieldsDoNotPreventOpeningTheScreen() {
        val snapshot = AppJson.decodeFromString(ControlPathsSnapshot.serializer(), """
            {"at":123,"future":true,"providers":[{"id":"example","label":"Example",
              "modes":null,"routes":[{"id":"auth","purpose":"auth","steps":[
                {"id":"account","label":"PC のログイン","protocol":null,"future":1}
              ]}]}]}
        """.trimIndent())
        assertEquals(1, snapshot.schemaVersion)
        assertEquals(emptyList<ControlMode>(), snapshot.providers.single().modes)
        assertNull(snapshot.providers.single().routes.single().steps.single().protocol)
        assertEquals(R.string.core_control_check_configuration.toString(), controlAvailabilityLabel("futureState") { id, _ -> id.toString() })
    }

    @Test
    fun unknownModeCountsStayDistinctFromAConfirmedEmptyCount() {
        val provider = AppJson.decodeFromString(ControlProvider.serializer(), """
            {"modes":[{"id":"unclassified","activeSessions":-1},{"id":"empty","activeSessions":0},{"id":"missing"}]}
        """.trimIndent())
        assertEquals(-1, provider.modes[0].activeSessions)
        assertEquals(0, provider.modes[1].activeSessions)
        assertEquals(-1, provider.modes[2].activeSessions)
    }
}

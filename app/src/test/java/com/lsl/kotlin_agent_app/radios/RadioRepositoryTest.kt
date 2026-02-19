package com.lsl.kotlin_agent_app.radios

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioRepositoryTest {

    private class FakeApi : RadioBrowserApi {
        var countriesCalls: Int = 0
        var stationsCalls: Int = 0

        override suspend fun listCountries(): List<RadioBrowserCountry> {
            countriesCalls += 1
            return listOf(
                RadioBrowserCountry(name = "China", stationCount = 123, iso3166_1 = "CN"),
                RadioBrowserCountry(name = "United States", stationCount = 456, iso3166_1 = "US"),
            )
        }

        override suspend fun listStationsByCountry(countryName: String, limit: Int): List<RadioBrowserStation> {
            stationsCalls += 1
            return listOf(
                RadioBrowserStation(
                    stationUuid = "uuid-1",
                    name = "Station A",
                    urlResolved = "https://example.com/a",
                    votes = 10,
                    codec = "MP3",
                    bitrate = 128,
                    country = countryName,
                ),
                RadioBrowserStation(
                    stationUuid = "uuid-2",
                    name = "Station B",
                    urlResolved = "https://example.com/b",
                    votes = 20,
                    codec = "AAC",
                    bitrate = 64,
                    country = countryName,
                ),
            ).take(limit.coerceAtLeast(0))
        }
    }

    @Test
    fun syncCountries_usesCacheWithinTtl() = runTest {
        val ctx = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(ctx)
        ws.ensureInitialized()

        val api = FakeApi()
        var now = 1_700_000_000_000L
        val repo =
            RadioRepository(
                ws = ws,
                api = api,
                nowMs = { now },
                countriesTtlMs = 72L * 3600L * 1000L,
            )

        val r1 = repo.syncCountries(force = false)
        assertTrue(r1.ok)
        assertEquals(1, api.countriesCalls)

        val idx = repo.readCountriesIndexOrNull()
        assertNotNull(idx)
        assertTrue(ws.exists(".agents/workspace/radios"))
        assertTrue(ws.exists(".agents/workspace/radios/favorites"))

        val r2 = repo.syncCountries(force = false)
        assertTrue(r2.ok)
        assertEquals(1, api.countriesCalls)

        now += 73L * 3600L * 1000L
        val r3 = repo.syncCountries(force = false)
        assertTrue(r3.ok)
        assertEquals(2, api.countriesCalls)
    }

    @Test
    fun syncStations_usesCacheWithinTtl() = runTest {
        val ctx = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(ctx)
        ws.ensureInitialized()

        val api = FakeApi()
        var now = 1_700_000_000_000L
        val repo =
            RadioRepository(
                ws = ws,
                api = api,
                nowMs = { now },
                stationsTtlMs = 72L * 3600L * 1000L,
            )

        repo.syncCountries(force = true)
        val idx = repo.readCountriesIndexOrNull()!!
        val cn = idx.countries.first { it.code == "CN" }

        val r1 = repo.syncStationsForCountryDir(countryDirName = cn.dir, force = false)
        assertTrue(r1.ok)
        assertEquals(1, api.stationsCalls)

        val countryDir = ".agents/workspace/radios/${cn.dir}"
        val hasRadio = ws.listDir(countryDir).any { it.name.endsWith(".radio") }
        assertTrue(hasRadio)

        val r2 = repo.syncStationsForCountryDir(countryDirName = cn.dir, force = false)
        assertTrue(r2.ok)
        assertEquals(1, api.stationsCalls)

        now += 73L * 3600L * 1000L
        val r3 = repo.syncStationsForCountryDir(countryDirName = cn.dir, force = false)
        assertTrue(r3.ok)
        assertEquals(2, api.stationsCalls)
    }
}


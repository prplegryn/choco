package app.chocola

import app.chocola.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VendorAccountsTest {
    @Test fun legacyModelsMigrateWithoutChangingIdsOrTuning() {
        val one = Provider(baseUrl = "https://api.deepseek.com", apiKey = "first", model = "one", maxTokens = 32768, compactPercent = 63)
        val two = one.copy(id = newId(), model = "two", protocol = Protocol.RESPONSES, extraBody = "{\"custom\":true}")
        val another = one.copy(id = newId(), apiKey = "second")
        val migration = VendorAccounts.migrate(emptyList(), listOf(one, two, another))
        assertEquals(2, migration.vendors.size)
        assertEquals(migration.models[0].vendorId, migration.models[1].vendorId)
        assertNotEquals(migration.models[0].vendorId, migration.models[2].vendorId)
        listOf(one, two, another).zip(migration.models).forEach { (old, migrated) ->
            assertEquals(old.copy(vendorId = migrated.vendorId), migrated)
        }
    }

    @Test fun migrationIsIdempotentAndRetainsAnEmptyVendor() {
        val empty = ApiVendor(brand = "Empty", baseUrl = "https://empty.example/v1", apiKey = "saved")
        val model = Provider(model = "legacy", apiKey = "legacy")
        val once = VendorAccounts.migrate(listOf(empty), listOf(model))
        val twice = VendorAccounts.migrate(once.vendors, once.models)
        assertEquals(once, twice)
        assertTrue(twice.vendors.contains(empty))
        assertTrue(twice.models.none { it.vendorId == empty.id })
        assertEquals(once, VendorAccounts.migrate(listOf(empty), listOf(model)))
    }

    @Test fun vendorOwnsCredentialsAndModelsKeepTheirProtocolAndParameters() {
        val vendor = ApiVendor(baseUrl = "https://new.example/v1", apiKey = "new", headers = mapOf("X-Key" to "new-value"))
        val model = Provider(model = "m", vendorId = vendor.id, baseUrl = "https://old.example", apiKey = "old", protocol = Protocol.RESPONSES,
            temperature = .6, reasoningEffort = "high", timeoutSeconds = 480, recentTurns = 5)
        val migrated = VendorAccounts.migrate(listOf(vendor), listOf(model)).models.single()
        assertEquals(model.copy(baseUrl = vendor.baseUrl, apiKey = vendor.apiKey, headers = vendor.headers), migrated)
        assertEquals(Protocol.RESPONSES, migrated.protocol)
    }

    @Test fun explicitlySeparateVendorsNeverMergeJustBecauseTheyShareAKey() {
        val first = ApiVendor(apiKey = "shared")
        val second = first.copy(id = newId(), brand = "Other")
        val migration = VendorAccounts.migrate(listOf(first, second), listOf(first.model("a"), second.model("b")))
        assertEquals(2, migration.vendors.size)
        assertEquals(first.id, migration.models[0].vendorId)
        assertEquals(second.id, migration.models[1].vendorId)
    }

    @Test fun missingVendorRecordCanBeRecoveredWithoutBreakingModelReferences() {
        val model = Provider(vendorId = "existing-vendor", model = "m", apiKey = "recover")
        val result = VendorAccounts.migrate(emptyList(), listOf(model))
        assertEquals(model, result.models.single())
        assertEquals("existing-vendor", result.vendors.single().id)
        assertEquals("recover", result.vendors.single().apiKey)
    }

    @Test fun emptyVendorAndModelOwnershipSurviveBackupSerialization() {
        val vendor = ApiVendor(brand = "Custom", protocol = Protocol.ANTHROPIC, apiKey = "secret", headers = mapOf("X-Custom" to "value"))
        val restored = ApiVendor.from(JSONObject(vendor.json().toString()))
        assertEquals(vendor, restored)
        val model = vendor.model("some-model")
        assertEquals(model, Provider.from(JSONObject(model.json().toString())))
        assertEquals(vendor.id, model.vendorId)
        assertEquals(Protocol.ANTHROPIC, model.protocol)
    }

    @Test fun recommendedParametersUseTheVendorsActualEndpointAndProtocol() {
        val profile = ModelCatalog.models.first { it.protocol == Protocol.RESPONSES }
        val vendor = ApiVendor(protocol = Protocol.CHAT, baseUrl = "https://gateway.example/v1", apiKey = "gateway")
        val model = vendor.model(profile.model)
        assertEquals(vendor.id, model.vendorId)
        assertEquals(vendor.baseUrl, model.baseUrl)
        assertEquals(vendor.apiKey, model.apiKey)
        assertEquals(Protocol.CHAT, model.protocol)
        assertEquals(profile.output, model.maxTokens)
        assertEquals(profile.context, model.contextWindow)
    }

    @Test fun invalidEndpointsAndHeaderNewlinesAreRejectedBeforeSaving() {
        listOf("", "not-a-url", "file:///tmp/file", "https://", "https://user:pass@example.com", "https://example.com?key=value").forEach {
            assertTrue(runCatching { ApiVendor(baseUrl = it).validate() }.isFailure)
        }
        assertTrue(runCatching { ApiVendor(headers = mapOf("X-Key" to "a\r\nb")).validate() }.isFailure)
        ApiVendor(baseUrl = "http://localhost:8080/v1").validate()
        ApiVendor(baseUrl = "https://api.example.com/v1/").validate()
    }
}

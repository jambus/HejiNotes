package com.jambus.heji

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncTaskPersistenceTest {
    private class Fixture {
        val values = mutableMapOf<String, String>()
        var writes = 0
        val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        lateinit var preferences: SharedPreferences
        init {
            preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getString" -> values[args!![0]] ?: args[1]
                    "edit" -> editor()
                    "registerOnSharedPreferenceChangeListener" -> { listeners.add(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener); null }
                    "unregisterOnSharedPreferenceChangeListener" -> { listeners.remove(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener); null }
                    else -> null
                }
            } as SharedPreferences
        }
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, String>()
            lateinit var proxy: SharedPreferences.Editor
            proxy = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString" -> { pending[args!![0] as String] = args[1] as String; proxy }
                    "apply", "commit" -> {
                        values.putAll(pending)
                        writes++
                        pending.keys.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(preferences, key) } }
                        if (method.name == "commit") true else null
                    }
                    else -> proxy
                }
            } as SharedPreferences.Editor
            return proxy
        }
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }
        fun store() = SyncTaskStateStore(context)
    }

    private fun legacy(vault: String, provider: String = "google_drive", status: String = "FAILED") =
        JSONObject().put("providerId", provider).put("providerName", provider).put("targetName", "Remote")
            .put("status", status).put("startedAt", 1).put("vaultId", vault)
            .put("message", "secret legacy message https://cloud.invalid/token")
            .put("errors", JSONArray().put(JSONObject().put("code", "ITEM_FAILED")
                .put("path", "content://sensitive/tree").put("reason", "NETWORK"))
                .put(JSONObject().put("code", "ITEM_FAILED").put("path", "old exception message")))

    @Test fun `raw legacy latest and history are actually sanitized and second read is idempotent`() {
        val fixture = Fixture()
        fixture.values["latest_task"] = legacy("content://vault/A").toString()
        fixture.values["provider_results_v1"] = JSONArray().put(legacy("content://vault/B", "onedrive")).toString()
        val store = fixture.store()
        assertEquals(SyncTaskVaultKey.fromVaultId("content://vault/A"), store.snapshot()!!.vaultId)
        assertNotNull(store.snapshot("content://vault/B", "onedrive"))
        val serialized = fixture.values.values.joinToString()
        assertFalse(serialized.contains("content://"))
        assertFalse(serialized.contains("secret legacy"))
        assertFalse(serialized.contains("old exception"))
        val latest = JSONObject(fixture.values.getValue("latest_task"))
        assertEquals("", latest.getJSONArray("errors").getJSONObject(0).getString("path"))
        val writes = fixture.writes
        store.snapshot()
        store.snapshot("content://vault/B", "onedrive")
        assertEquals(writes, fixture.writes)
    }

    @Test fun `scoped rejection notifies requester while preserving global running task and other results`() {
        for (provider in listOf("google_drive", "onedrive")) {
            val fixture = Fixture()
            fixture.values["provider_results_v1"] = JSONArray().put(legacy("content://vault/C", "onedrive")).toString()
            val service = fixture.store()
            val active = service.begin("google_drive", "Google Drive", "A remote", "content://vault/A")!!
            val latest = fixture.values.getValue("latest_task")
            val activity = fixture.store()
            val before = activity.snapshot("content://vault/B", provider)
            var events = 0
            val unsubscribe = activity.observe { events++ }
            assertNull(service.begin(provider, provider, "B remote", "content://vault/B"))
            val rejected = service.rejectStart(provider, provider, "B remote", "content://vault/B")
            assertEquals(latest, fixture.values["latest_task"])
            assertEquals(active, service.snapshot())
            assertEquals(SyncErrorReason.SYNC_BUSY, rejected.errors.single().reason)
            assertTrue(SyncConfirmationPolicy.shouldShowResult(before, activity.snapshot("content://vault/B", provider)))
            assertNotNull(activity.snapshot("content://vault/C", "onedrive"))
            assertTrue(events > 0)
            unsubscribe()
            // Refusal does not cancel the original run, which can still progress and finish normally.
            assertEquals(SyncTaskStatus.SUCCEEDED, service.finish(DriveSyncResult(0, 0, 0, 0, emptyList(), false))!!.status)
        }
    }

    @Test fun `concurrent migration and progress preserve active identity and latest progress`() {
        val fixture = Fixture()
        fixture.values["latest_task"] = legacy("content://vault/A", status = "RUNNING").toString()
        fixture.values["provider_results_v1"] = JSONArray().put(legacy("content://vault/B", "onedrive")).toString()
        val signal = CountDownLatch(1)
        val migration = Thread { signal.await(); repeat(20) { fixture.store().snapshot("content://vault/B", "onedrive") } }
        val progress = Thread { signal.await(); repeat(20) { fixture.store().updateProgress(DriveSyncProgress(it + 1, 20, "")) } }
        migration.start(); progress.start(); signal.countDown()
        migration.join(); progress.join()
        val result = fixture.store().snapshot()!!
        assertTrue(result.isRunning)
        assertEquals(20, result.completed)
        assertEquals(SyncTaskVaultKey.fromVaultId("content://vault/A"), result.vaultId)
        assertFalse(fixture.values.values.joinToString().contains("content://"))
    }

    @Test fun `new refusal supersedes old completed latest only in the scoped result`() {
        val fixture = Fixture()
        val store = fixture.store()
        store.begin("onedrive", "OneDrive", "Remote", "content://vault/B")
        store.finish(DriveSyncResult(0, 0, 0, 0, emptyList(), false))
        val before = store.snapshot("content://vault/B", "onedrive")
        val latest = fixture.values["latest_task"]
        val refused = store.rejectStart("onedrive", "OneDrive", "Remote", "content://vault/B")
        assertEquals(latest, fixture.values["latest_task"])
        assertEquals(refused, store.snapshot("content://vault/B", "onedrive"))
        assertTrue(SyncConfirmationPolicy.shouldShowResult(before, refused))
    }
}

package com.jambus.heji

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncTaskObservationTest {
    private class PreferencesFixture {
        val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "registerOnSharedPreferenceChangeListener" -> listeners.add(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener)
                "unregisterOnSharedPreferenceChangeListener" -> listeners.remove(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener)
            }
            null
        } as SharedPreferences
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        fun emit(key: String) = listeners.toList().forEach { it.onSharedPreferenceChanged(preferences, key) }
    }

    @Test fun `observer sees task updates from another store and ignores unrelated preferences`() {
        val fixture = PreferencesFixture()
        var updates = 0
        val stop = SyncTaskStateStore(fixture.context).observe { updates++ }
        fixture.emit("unrelated")
        assertEquals(0, updates)
        fixture.emit("latest_task") // SharedPreferences is shared by Activity and Service stores.
        fixture.emit("latest_task")
        assertEquals(2, updates)
        stop()
        fixture.emit("latest_task")
        assertEquals(2, updates)
        assertEquals(0, fixture.listeners.size)
    }

    @Test fun `stop and restart subscription does not accumulate callbacks`() {
        val fixture = PreferencesFixture()
        val store = SyncTaskStateStore(fixture.context)
        var updates = 0
        store.observe { updates++ }.invoke()
        val stop = store.observe { updates++ }
        fixture.emit("latest_task")
        assertEquals(1, updates)
        stop()
        assertEquals(0, fixture.listeners.size)
    }
}

package com.thomaswcode.whatsappscheduler.data.db

import android.app.Application
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T1: the v1 -> v2 migration against the exported v1 schema. v2 ships while messages are pending,
 * so schedules and their history must come through intact, not merely open without crashing.
 * Uses the driver-based helper: the SupportSQLite one rejects Windows paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MigrationTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val file = context.getDatabasePath("migration-test")

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = file,
        driver = AndroidSQLiteDriver(),
        databaseClass = AppDatabase::class,
    )

    private fun createV1() {
        helper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO schedules (id, message, targetKind, targetName, phoneE164, isLocked, firstAtMillis, zoneId, recurrence, nextAtMillis, enabled, createdAtMillis, lastRunAtMillis, lastResult) " +
                    "VALUES (7, 'Happy birthday', 'CONTACT', 'Mum', '+447400123456', 1, 1790668800000, 'Europe/London', 'DAILY', 1790755200000, 1, 1790000000000, 1790668835000, 'SENT')"
            )
            execSQL("INSERT INTO send_log (id, scheduleId, atMillis, status, targetName, detail) VALUES (1, 7, 1790668835000, 'SENT', 'Mum', NULL)")
            execSQL("INSERT INTO send_log (id, scheduleId, atMillis, status, targetName, detail) VALUES (2, 7, 1790582435000, 'FAILED', 'Mum', 'search: Timed out')")
            close()
        }
    }

    private fun SQLiteConnection.rows(sql: String): List<List<String?>> = prepare(sql).use { statement ->
        buildList {
            while (statement.step()) {
                add((0 until statement.getColumnCount()).map { if (statement.isNull(it)) null else statement.getText(it) })
            }
        }
    }

    @Test
    fun schedulesAndHistoryComeThroughUnchanged() {
        createV1()
        val db = helper.runMigrationsAndValidate(2)
        val schedule = db.rows(
            "SELECT message, targetName, phoneE164, isLocked, nextAtMillis, lastResult, recipientId, batchId, attention, lastStatus FROM schedules WHERE id = 7"
        ).single()
        assertEquals(listOf("Happy birthday", "Mum", "+447400123456", "1", "1790755200000", "SENT"), schedule.take(6))
        assertTrue("new columns start empty", schedule.drop(6).all { it == null })
        val history = db.rows("SELECT id, scheduleId, status, detail, occurrenceAtMillis FROM send_log ORDER BY id")
        assertEquals(
            "history rows and their links kept",
            listOf(listOf("1", "7", "SENT", null, null), listOf("2", "7", "FAILED", "search: Timed out", null)),
            history,
        )
        db.close()
    }

    @Test
    fun afterMigrationHistoryOutlivesItsSchedule() = runTest {
        createV1()
        helper.runMigrationsAndValidate(2).close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath).build()
        try {
            room.scheduleDao().delete(room.scheduleDao().getById(7)!!)
            val logs = room.sendLogDao().observeRecent(10).first()
            assertEquals(2, logs.size)
            logs.forEach { assertNull(it.scheduleId) }
        } finally {
            room.close()
        }
    }
}

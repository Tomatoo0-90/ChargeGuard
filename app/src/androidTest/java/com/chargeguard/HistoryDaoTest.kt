package com.chargeguard

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chargeguard.data.history.HistoryDao
import com.chargeguard.data.history.HistoryDatabase
import com.chargeguard.data.history.HistoryEventEntity
import com.chargeguard.data.history.RoomHistoryRepository
import com.chargeguard.domain.model.HistoryEventType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDaoTest {
    private lateinit var db: HistoryDatabase
    private lateinit var dao: HistoryDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HistoryDatabase::class.java).build()
        dao = db.historyDao()
    }

    @After fun tearDown() = db.close()

    @Test fun newestFirst_andTypesRoundTrip() = runBlocking {
        val repo = RoomHistoryRepository(dao) { 0L }
        repo.record(HistoryEventType.PROTECTION_ACTIVATED, null, 1_000)
        repo.record(HistoryEventType.ALARM_STARTED, "detail", 2_000)
        val events = repo.observe(10).first()
        assertEquals(listOf(HistoryEventType.ALARM_STARTED, HistoryEventType.PROTECTION_ACTIVATED), events.map { it.type })
        assertEquals("detail", events.first().detail)
    }

    @Test fun prune_keepsNewest() = runBlocking {
        repeat(20) { dao.insert(HistoryEventEntity(type = HistoryEventType.ALARM_STARTED.name, timestamp = it.toLong(), detail = null)) }
        dao.prune(5)
        assertEquals(5, dao.count())
        assertEquals(19L, dao.observe(1).first().single().timestamp)
    }

    @Test fun unknownTypesAreSkipped_notCrashing() = runBlocking {
        dao.insert(HistoryEventEntity(type = "SOMETHING_REMOVED", timestamp = 1, detail = null))
        val repo = RoomHistoryRepository(dao) { 0L }
        assertEquals(0, repo.observe(10).first().size)
    }
}

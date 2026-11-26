package com.chargeguard.data.history

import com.chargeguard.domain.model.HistoryEvent
import com.chargeguard.domain.model.HistoryEventType
import com.chargeguard.domain.repository.Clock
import com.chargeguard.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomHistoryRepository @Inject constructor(
    private val dao: HistoryDao,
    private val clock: Clock,
) : HistoryRepository {

    override fun observe(limit: Int): Flow<List<HistoryEvent>> = dao.observe(limit).map { rows ->
        rows.mapNotNull { row ->
            val type = HistoryEventType.entries.firstOrNull { it.name == row.type } ?: return@mapNotNull null
            HistoryEvent(row.id, type, row.timestamp, row.detail)
        }
    }

    override suspend fun record(type: HistoryEventType, detail: String?, timestampMillis: Long?) {
        dao.insert(HistoryEventEntity(type = type.name, timestamp = timestampMillis ?: clock.now(), detail = detail))
        dao.prune(MAX_EVENTS)
    }

    override suspend fun clear() = dao.clear()

    private companion object {
        const val MAX_EVENTS = 500
    }
}

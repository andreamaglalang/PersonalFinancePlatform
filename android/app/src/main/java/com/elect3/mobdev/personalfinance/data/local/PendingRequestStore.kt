package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.data.local.room.PendingRequestDao
import com.elect3.mobdev.personalfinance.data.local.room.PendingRequestEntity

/**
 * Uncertain financial writes (savings contributions, bill payments) keyed by (account namespace, request path).
 * The body is the exact JSON payload, including its ClientId, so every retry replays the same identity.
 */
interface PendingRequestStore {
    suspend fun find(namespace: String, path: String): String?
    suspend fun paths(namespace: String): List<String>
    suspend fun save(namespace: String, path: String, body: String)
    suspend fun remove(namespace: String, path: String)
}

/** Process-lifetime only (tests, hosts without a database). Production uses [RoomPendingRequestStore]. */
class InMemoryPendingRequestStore : PendingRequestStore {
    private val rows = mutableMapOf<Pair<String, String>, String>()
    override suspend fun find(namespace: String, path: String) = synchronized(rows) { rows[namespace to path] }
    override suspend fun paths(namespace: String) = synchronized(rows) { rows.keys.filter { it.first == namespace }.map { it.second }.sorted() }
    override suspend fun save(namespace: String, path: String, body: String) { synchronized(rows) { rows[namespace to path] = body } }
    override suspend fun remove(namespace: String, path: String) { synchronized(rows) { rows.remove(namespace to path) } }
}

class RoomPendingRequestStore(private val dao: PendingRequestDao, private val now: () -> Long = System::currentTimeMillis) : PendingRequestStore {
    override suspend fun find(namespace: String, path: String) = dao.find(namespace, path)?.body
    override suspend fun paths(namespace: String) = dao.paths(namespace)
    override suspend fun save(namespace: String, path: String, body: String) = dao.upsert(PendingRequestEntity(namespace, path, body, now()))
    override suspend fun remove(namespace: String, path: String) = dao.delete(namespace, path)
}

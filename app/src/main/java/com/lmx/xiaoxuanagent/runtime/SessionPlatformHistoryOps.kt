package com.lmx.xiaoxuanagent.runtime

internal object SessionPlatformHistoryOps {
    fun readSessionHistory(
        limit: Int = 24,
    ): SessionHistorySnapshot = SessionHistoryService.readHistory(limit = limit)

    fun searchSessionHistory(
        query: String,
        limit: Int = 12,
    ): SessionHistorySearchResult = SessionHistoryService.searchHistory(query = query, limit = limit)
}


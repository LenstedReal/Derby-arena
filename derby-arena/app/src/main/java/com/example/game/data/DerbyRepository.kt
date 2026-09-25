package com.example.game.data

import kotlinx.coroutines.flow.Flow

class DerbyRepository(private val derbyDao: DerbyDao) {
    val saveState: Flow<DerbySaveState?> = derbyDao.getSaveState()

    suspend fun updateSaveState(state: DerbySaveState) {
        derbyDao.saveState(state)
    }

    suspend fun resetSave() {
        derbyDao.clearSave()
    }
}

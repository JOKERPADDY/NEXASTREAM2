package com.nexastream.app.utils

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Utility class to notify ViewModels when the current provider changes
 */
object ProviderChangeNotifier {
    private val _providerChangeFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val providerChangeFlow: Flow<Unit> = _providerChangeFlow.asSharedFlow()
    
    /**
     * Notify all listeners that the provider has changed
     */
    fun notifyProviderChanged() {
        _providerChangeFlow.tryEmit(Unit)
    }
}

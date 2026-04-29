package com.localbridge.android.core.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class LocalBridgeViewModelFactory<T : ViewModel>(
    private val creator: () -> T
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
        return creator() as VM
    }
}

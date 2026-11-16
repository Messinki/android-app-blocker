package com.appblocker

import android.app.Application
import com.appblocker.data.BlockRepository

class BlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BlockRepository.init(this)
    }
}

package com.axion.floatingroot

import android.app.Application
import com.topjohnwu.superuser.Shell

class RootApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(Shell.Builder.create().setContext(this))
    }
}

package com.arghya.buddyonthego

import android.app.Application
import android.content.Context
import com.arghya.buddyonthego.alarm.Notifs
import com.arghya.buddyonthego.data.AppDb
import com.arghya.buddyonthego.data.Settings
import com.arghya.buddyonthego.net.NetworkGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object AppScope {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** Tiny service locator (keeps receivers and services simple; replaces Hilt for this build). */
object ServiceLocator {
    lateinit var db: AppDb
        private set
    lateinit var settings: Settings
        private set
    lateinit var gate: NetworkGate
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (::db.isInitialized) return
        val c = ctx.applicationContext
        db = AppDb.build(c)
        settings = Settings(c)
        gate = NetworkGate(c, settings)
    }
}

class BuddyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        Notifs.createChannels(this)
    }
}

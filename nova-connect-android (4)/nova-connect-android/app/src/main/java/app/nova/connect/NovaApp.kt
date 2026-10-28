package app.nova.connect

import android.app.Application

class NovaApp : Application() {
    override fun onCreate() { super.onCreate(); Core.init(this) }
}

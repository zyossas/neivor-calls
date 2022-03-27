package neivor.id.calls

import android.app.Application

class NeivorCallsAplication: Application() {

    private var backend: PlivoBackEnd? = null

    override fun onCreate() {
        super.onCreate()
        Utils.options["context"] = applicationContext
        Utils.options["sharedContext"] = applicationContext
        backend = PlivoBackEnd.newInstance()
        backend?.init(BuildConfig.DEBUG)
    }

    fun backend(): PlivoBackEnd? {
        return backend
    }
}
package com.sheen.rokid.phone.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log

/** Debug-only host entry that starts the loopback P7 replacement page and exits; the server thread stays in-process. */
class T6PageServerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val started = T6PageServer.start()
        Log.i("EdithT6Page", "T6_PAGE_ACTIVITY started=$started url=http://127.0.0.1:${T6PageServer.PORT}/")
        finish()
    }
}

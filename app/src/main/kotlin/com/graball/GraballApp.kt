package com.graball

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GraballApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var engineReady = false
        private set

    override fun onCreate() {
        super.onCreate()
        // native lib extraction is slow on first run; never block main thread
        appScope.launch(Dispatchers.IO) {
            // sweep cookie exports a SIGKILL may have orphaned (finally never ran)
            java.io.File(noBackupFilesDir, "cookies").deleteRecursively()
            try {
                YoutubeDL.getInstance().init(this@GraballApp)
                FFmpeg.getInstance().init(this@GraballApp)
                engineReady = true
            } catch (e: Exception) {
                Log.e("Graball", "engine init failed", e)
            }
        }
    }
}

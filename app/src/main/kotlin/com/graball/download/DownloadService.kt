package com.graball.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.graball.cookies.CookieExport
import com.graball.prefs.Prefs
import com.graball.resolve.classifyError
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore

// yt-dlp's -o template bakes our row id into the temp filename (title-<id>.ext, or
// title-<id>.f137.mp4 for a leftover fragment name) -- strip just that "-<id>" token before
// it's shown to the user. Anchored like isTempOf() so an id-shaped substring earlier in the
// title can't false-match. Never touch the on-disk file: cleanup/resume regexes need it there.
// No-op for direct downloads, whose files are already clean.
internal fun publishName(name: String, id: Long): String =
    Regex("-$id((?:\\.[A-Za-z0-9]+)+)$").replace(name) { it.groupValues[1] }

/** dataSync foreground service: the only writer of DownloadEntity. UI just observes the DB. */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // all DownloadEntity writes go through one lane so a throttled progress write can never
    // land after (and resurrect) a terminal DONE/FAILED write
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    private val dbCtx = Dispatchers.IO.limitedParallelism(1)
    private val semaphore = Semaphore(MAX_CONCURRENT)
    private lateinit var dao: DownloadDao
    private var loopJob: Job? = null
    @Volatile private var lastStartId = -1
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        dao = GraballDb.getInstance(this).downloadDao()
        // crash recovery once per process, BEFORE any loop: a second loop start must never
        // requeue rows a live loop is still downloading
        serviceScope.launch { dao.resetStale() }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        startForegroundNotification()
        ensureLoopRunning()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun startForegroundNotification() {
        val notif = buildNotification("Starting downloads…", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // tapping either notification just brings the app to front -- no deep link needed
    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, com.graball.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(text: String, progress: Int): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Graball")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setContentIntent(contentIntent())
            .build()

    private fun notifyProgress(list: List<DownloadEntity>) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 1000) return
        val active = list.filter { it.status in Status.ACTIVE }
        if (active.isEmpty()) return
        lastNotifyAt = now
        val avgPct = active.map { it.progressPct }.average().toInt()
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification("${active.size} downloading… $avgPct%", avgPct))
    }

    private fun finishNotification() {
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Graball")
            .setContentText("All downloads finished")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setContentIntent(contentIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    /** One worker loop per service lifetime: pulls QUEUED rows, bounds concurrency with a
     * Semaphore(2), stops the service once nothing is queued or running. */
    @Synchronized
    private fun ensureLoopRunning() {
        if (loopJob?.isActive == true) return
        loopJob = serviceScope.launch {
            val notifJob = launch { dao.observeAll().collect { notifyProgress(it) } }
            val jobs = mutableListOf<Job>()
            while (true) {
                jobs.removeAll { it.isCompleted }
                val item = dao.nextQueued()
                if (item != null) {
                    semaphore.acquire()
                    dao.update(item.copy(status = Status.RUNNING))
                    jobs += launch {
                        try {
                            runItem(item.id)
                        } finally {
                            semaphore.release()
                        }
                    }
                    continue
                }
                if (jobs.isEmpty()) {
                    // close the enqueue race: a row inserted after the last poll but before
                    // stopSelf would be stranded (onStartCommand sees this loop still active)
                    if (dao.nextQueued() != null) continue
                    break
                }
                jobs.first().join() // waits if still running; instant no-op if already done
            }
            notifJob.cancel()
            // from here a fresh onStartCommand must be able to spawn a new loop
            loopJob = null
            if (dao.nextQueued() != null) {
                ensureLoopRunning() // row landed while we were shutting down
                return@launch
            }
            // delete-all mid-run empties the table: "All downloads finished" would be a lie then
            if (dao.countAll() > 0) finishNotification()
            else getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(lastStartId) // ignored if a newer start command arrived — its loop lives on
        }
    }

    private suspend fun runItem(id: Long) {
        var current = dao.getById(id) ?: return
        if (!storageOk(current)) {
            dao.update(current.copy(status = Status.FAILED, errorClass = "STORAGE_FULL"))
            return
        }
        val tmpDir = File(cacheDir, "downloads").apply { mkdirs() }
        val isDirect = current.formatId == com.graball.resolve.DIRECT_FORMAT
        val outTemplate = "${tmpDir.absolutePath}/%(title).100B-$id.%(ext)s"
        val request = YoutubeDLRequest(current.url).apply {
            // DIRECT_FORMAT is our sentinel, not a yt-dlp selector: let yt-dlp fetch the URL as-is
            if (current.formatId != com.graball.resolve.DIRECT_FORMAT) {
                addOption("-f", if (current.needsMux) "${current.formatId}+bestaudio" else current.formatId)
            }
            addOption("-o", outTemplate)
            addOption("--no-mtime")
            addOption("--no-warnings")
            addOption("--concurrent-fragments", "4")
            addOption("--progress-template", PROGRESS_TEMPLATE)
        }
        var lastDbWrite = 0L
        val runEngine = {
            YoutubeDL.getInstance().execute(request, id.toString()) { _, _, line ->
                if (line.contains("[Merger]") || line.contains("[ffmpeg]")) {
                    if (current.status != Status.MUXING) {
                        current = current.copy(status = Status.MUXING)
                        val snapshot = current
                        serviceScope.launch(dbCtx) { updateIfLive(snapshot) }
                    }
                    return@execute
                }
                val p = parseProgressLine(line) ?: return@execute
                current = current.copy(
                    progressPct = p.percent(),
                    downloadedBytes = p.dl.toLong(),
                    totalBytes = p.tot.toLong(),
                    etaSec = p.eta.toLong(),
                    fragIndex = p.fi,
                    fragCount = p.fc,
                )
                val now = System.currentTimeMillis()
                if (now - lastDbWrite >= 500) { // throttle DB writes to ~2/sec
                    lastDbWrite = now
                    val snapshot = current
                    serviceScope.launch(dbCtx) { updateIfLive(snapshot) }
                }
            }
            Unit
        }
        try {
            // one batch of direct files (images, pdfs, zips...) no longer boots python per item
            var directFile: File? = null
            if (isDirect) {
                directFile = DirectDownloader.download(
                    context = this,
                    url = current.url,
                    destDir = tmpDir,
                    baseName = current.title,
                    id = id,
                    onProgress = { downloaded, total ->
                        current = current.copy(
                            progressPct = if (total > 0) downloaded * 100f / total else 0f,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                        )
                        val now = System.currentTimeMillis()
                        if (now - lastDbWrite >= 500) { // throttle DB writes to ~2/sec
                            lastDbWrite = now
                            val snapshot = current
                            serviceScope.launch(dbCtx) { updateIfLive(snapshot) }
                        }
                    },
                )
            } else {
                // cookies exported per item, only for the life of this one engine call
                val domain = current.cookieDomain
                if (domain != null) {
                    CookieExport.withCookieFile(this, "https://$domain") { path ->
                        if (path != null) request.addOption("--cookies", path)
                        runEngine()
                    }
                } else {
                    runEngine()
                }
            }
            current = current.copy(status = Status.MOVING)
            kotlinx.coroutines.withContext(dbCtx) { dao.update(current) }
            // direct branch already holds the exact File; yt-dlp branch still needs to scan for it
            val outFile = directFile ?: findOutputFile(tmpDir, id)
            val mediaUri = outFile?.let { publishToMediaStore(current, it) }
            current = if (mediaUri != null) {
                current.copy(status = Status.DONE, mediaUri = mediaUri.toString(), progressPct = 100f)
            } else {
                current.copy(status = Status.FAILED, errorClass = "UNKNOWN", rawLog = "output file not found after download")
            }
            kotlinx.coroutines.withContext(dbCtx) { dao.update(current) }
        } catch (e: DirectDownloader.Cancelled) {
            handleCancellation(id, tmpDir)
        } catch (e: YoutubeDL.CanceledException) {
            handleCancellation(id, tmpDir)
        } catch (e: YoutubeDLException) {
            // never log e.message (may contain URL/cookie text) -- store to DB only
            kotlinx.coroutines.withContext(dbCtx) { dao.update(current.copy(status = Status.FAILED, errorClass = classifyError(e.message).name, rawLog = truncate(e.message))) }
            cleanupTemp(tmpDir, id)
        } catch (e: Exception) {
            kotlinx.coroutines.withContext(dbCtx) { dao.update(current.copy(status = Status.FAILED, errorClass = "UNKNOWN", rawLog = truncate(e.message))) }
            cleanupTemp(tmpDir, id)
        }
    }

    /** Progress writes only apply while the row is still live — never resurrect DONE/FAILED. */
    private suspend fun updateIfLive(snapshot: DownloadEntity) {
        val row = dao.getById(snapshot.id) ?: return
        if (row.status in Status.ACTIVE) dao.update(snapshot)
    }

    /** A killed engine/coroutine throws the same way whether cancel() or pause() triggered it.
     * pause() writes PAUSED (and keeps temp files) before signalling the kill, so if the row is
     * no longer ACTIVE by the time we get here, someone else already landed a terminal/paused
     * write — don't clobber it back to FAILED and don't delete its files. */
    private suspend fun handleCancellation(id: Long, tmpDir: File) {
        val row = dao.getById(id) ?: return
        if (row.status !in Status.ACTIVE) return
        kotlinx.coroutines.withContext(dbCtx) { dao.update(row.copy(status = Status.FAILED, errorClass = "CANCELLED")) }
        cleanupTemp(tmpDir, id)
    }

    private fun truncate(s: String?): String = (s ?: "").take(8 * 1024)

    private fun storageOk(entity: DownloadEntity): Boolean {
        val free = StatFs(cacheDir.path).availableBytes
        val need = maxOf(entity.totalBytes, 500L * 1024 * 1024)
        return free >= need
    }

    // matches "...-<id>.mp4", fragment "...-<id>.f137.mp4", partial "...-<id>.mp4.part"
    private fun isTempOf(name: String, id: Long) =
        Regex("-$id(\\.[A-Za-z0-9]+)+$").containsMatchIn(name)

    private fun findOutputFile(tmpDir: File, id: Long): File? =
        tmpDir.listFiles()?.firstOrNull { isTempOf(it.name, id) && !it.name.endsWith(".part") }

    private fun cleanupTemp(tmpDir: File, id: Long) {
        tmpDir.listFiles()?.filter { isTempOf(it.name, id) }?.forEach { it.delete() }
    }

    private fun mimeFor(ext: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"

    private fun collectionFor(mime: String): Pair<Uri, String> = when {
        mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/Graball"
        mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/Graball"
        mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Graball"
        else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/Graball"
    }

    private fun kindFor(mime: String): String = when {
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        mime.startsWith("image/") -> "image"
        else -> "other"
    }

    /** Publishes an already-located output file (yt-dlp temp file or a DirectDownloader result)
     * and deletes the temp copy. Prefers a user-picked SAF folder for this media kind; falls back
     * to MediaStore on any failure (unset pref, revoked permission, io error) so a finished
     * download is never lost. */
    private suspend fun publishToMediaStore(entity: DownloadEntity, file: File): Uri? {
        val mime = mimeFor(file.extension)
        val name = publishName(file.name, entity.id)
        val treeUri = Prefs.folderFor(this, kindFor(mime)).first()
        if (treeUri != null) {
            publishToTree(file, mime, name, treeUri)?.let { return it }
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishModern(file, mime, name) else publishLegacy(file, mime, name)
        } catch (e: Exception) {
            null
        }
    }

    /** Copies into a user-picked SAF tree via DocumentsContract. Null on any failure -- caller
     * falls back to MediaStore. */
    private fun publishToTree(file: File, mime: String, name: String, treeUriStr: String): Uri? = try {
        val tree = Uri.parse(treeUriStr)
        val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val docUri = DocumentsContract.createDocument(contentResolver, treeDocUri, mime, name)
            ?: return null
        try {
            val out = contentResolver.openOutputStream(docUri) ?: return null
            out.use { FileInputStream(file).use { inp -> inp.copyTo(it) } }
        } catch (e: Exception) {
            // don't leave a partial document in the user's folder
            runCatching { DocumentsContract.deleteDocument(contentResolver, docUri) }
            throw e
        }
        file.delete()
        docUri
    } catch (e: Exception) {
        null
    }

    private fun publishModern(file: File, mime: String, name: String): Uri? {
        val (collection, relDir) = collectionFor(mime)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values) ?: return null
        try {
            contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null) // never leave an invisible IS_PENDING row
            throw e
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        file.delete()
        return uri
    }

    // API < 29: no scoped-storage MediaStore.Downloads / IS_PENDING; needs
    // WRITE_EXTERNAL_STORAGE (maxSdkVersion=28) + legacy public dir + scanner.
    @Suppress("DEPRECATION")
    private suspend fun publishLegacy(file: File, mime: String, name: String): Uri? {
        val dirType = when {
            mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
            mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val publicDir = File(Environment.getExternalStoragePublicDirectory(dirType), "Graball").apply { mkdirs() }
        val dest = File(publicDir, name)
        FileInputStream(file).use { inp -> FileOutputStream(dest).use { out -> inp.copyTo(out) } }
        file.delete()
        return suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), arrayOf(mime)) { _, uri ->
                if (cont.isActive) cont.resume(uri ?: Uri.fromFile(dest))
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 1
        private const val MAX_CONCURRENT = 2

        // matches progress.* fields consumed by ProgressLine in Progress.kt; never parse stderr
        private const val PROGRESS_TEMPLATE =
            "download:{\"pct\":\"%(progress._percent_str)s\",\"dl\":%(progress.downloaded_bytes|0)s," +
                "\"tot\":%(progress.total_bytes|progress.total_bytes_estimate|0)s,\"eta\":%(progress.eta|0)s," +
                "\"fi\":%(progress.fragment_index|0)s,\"fc\":%(progress.fragment_count|0)s}"

        fun cancel(context: Context, id: Long) {
            val dao = GraballDb.getInstance(context).downloadDao()
            CoroutineScope(Dispatchers.IO).launch {
                val e = dao.getById(id) ?: return@launch
                if (e.formatId == com.graball.resolve.DIRECT_FORMAT) {
                    DirectDownloader.cancel(id)
                } else {
                    YoutubeDL.getInstance().destroyProcessById(id.toString()) // forks pstree+kill: never on main
                }
                dao.update(e.copy(status = Status.FAILED, errorClass = "CANCELLED", rawLog = null))
                File(context.cacheDir, "downloads").listFiles()
                    ?.filter { Regex("-$id(\\.[A-Za-z0-9]+)+$").containsMatchIn(it.name) }
                    ?.forEach { it.delete() }
            }
        }

        fun retry(context: Context, id: Long) {
            val dao = GraballDb.getInstance(context).downloadDao()
            CoroutineScope(Dispatchers.IO).launch {
                val e = dao.getById(id) ?: return@launch
                dao.update(e.copy(status = Status.QUEUED, errorClass = null, rawLog = null))
                context.startForegroundService(Intent(context, DownloadService::class.java))
            }
        }

        /** Unlike cancel(), leaves temp/partial files on disk -- that's the entire point of a
         * pause. Status is written BEFORE the kill signal so runItem's cancellation catch (which
         * can't otherwise tell a pause from a cancel) sees PAUSED already committed and skips its
         * own terminal write + cleanup. */
        fun pause(context: Context, id: Long) {
            val dao = GraballDb.getInstance(context).downloadDao()
            CoroutineScope(Dispatchers.IO).launch {
                val e = dao.getById(id) ?: return@launch
                dao.update(e.copy(status = Status.PAUSED))
                if (e.formatId == com.graball.resolve.DIRECT_FORMAT) {
                    DirectDownloader.cancel(id)
                } else {
                    YoutubeDL.getInstance().destroyProcessById(id.toString()) // forks pstree+kill: never on main
                }
            }
        }

        /** yt-dlp resumes via --continue (on by default) against the stable "-$id" temp name;
         * DirectDownloader.download() resumes from the partial file it left behind on pause(). */
        fun resume(context: Context, id: Long) {
            val dao = GraballDb.getInstance(context).downloadDao()
            CoroutineScope(Dispatchers.IO).launch {
                val e = dao.getById(id) ?: return@launch
                dao.update(e.copy(status = Status.QUEUED, errorClass = null, rawLog = null))
                context.startForegroundService(Intent(context, DownloadService::class.java))
            }
        }
    }
}

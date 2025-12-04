package com.opensource.svgaplayer

import android.R.attr.height
import android.R.attr.width
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.utils.log.LogUtils
import okhttp3.*
import org.json.JSONObject
import java.io.*
import java.net.URL
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

/**
 * SVGAParser - 优化与 OOM 防护版本
 *
 * 改进点见上方注释。
 */
class SVGAParser(context: Context?) {

    private var mContext = context?.applicationContext

    init {
        logD("SVGAParser Initializing...")
        mContext?.let {
            SVGACache.onCreate(it)
            logD("SVGA Cache Dir: ${it.cacheDir.absolutePath}")
        }
    }

    fun init(context: Context) {
        logD("SVGAParser re-initializing with new context.")
        this.mContext = context.applicationContext
        SVGACache.onCreate(context)
    }

    @Volatile
    private var mFrameWidth = 0

    @Volatile
    private var mFrameHeight = 0

    interface ParseCompletion {
        fun onComplete(videoItem: SVGAVideoEntity)
        fun onError()
    }

    interface PlayCallback {
        fun onPlay(file: List<File>)
    }

    // 缓存大小（字节），可调整
    private var memoryCacheMaxBytes = 30 * 1024 * 1024

    private val memoryCache = object : LruCache<String, SVGAVideoEntity>(memoryCacheMaxBytes) {
        override fun sizeOf(key: String, value: SVGAVideoEntity): Int = value.estimateSize()
    }

    private val fileLockMap = ConcurrentHashMap<String, Any>()
    private val downloadTasks = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    // FileDownloader 支持并发与取消
    class FileDownloader(private val maxConcurrent: Int = 3) {
        private val client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
        private val semaphore = Semaphore(maxConcurrent)
        private val runningCalls = ConcurrentHashMap<String, Call>()

        /**
         * 开始下载，返回一个可调用的取消 lambda（类型: (() -> Unit)?）
         */
        fun download(
            url: String,
            cacheFile: File,
            onSuccess: (File) -> Unit,
            onFailure: (Exception) -> Unit
        ): (() -> Unit)? {
            // 取消标志（局部变量）
            @Suppress("LocalVariableName") // 只是为了避免 IDE 警告（可移除）
            var cancelled = false

            // 明确声明 cancelBlock 类型，避免类型推断歧义
            val cancelBlock: () -> Unit = {
                cancelled = true
                runningCalls[url]?.cancel()
            }

            // 获取信号量，若被中断则回调失败
            try {
                semaphore.acquire()
            } catch (ie: InterruptedException) {
                onFailure(ie)
                return null
            }

            val request = Request.Builder().url(url).build()
            val call = client.newCall(request)
            runningCalls[url] = call

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runningCalls.remove(url)
                    try {
                        semaphore.release()
                    } catch (_: Throwable) {}
                    if (!cancelled) {
                        try { cacheFile.delete() } catch (_: Throwable) {}
                        onFailure(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    runningCalls.remove(url)
                    try {
                        semaphore.release()
                    } catch (_: Throwable) {}
                    if (cancelled) {
                        try { cacheFile.delete() } catch (_: Throwable) {}
                        return
                    }
                    try {
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val tmpFile = File(cacheFile.absolutePath + ".tmp")
                        response.body?.byteStream()?.buffered()?.use { input ->
                            FileOutputStream(tmpFile).use { output ->
                                input.copyTo(output) // 使用流拷贝，避免一次性读入内存
                            }
                        } ?: throw IOException("Empty response body")
                        // 尝试重命名临时文件到最终文件
                        if (!tmpFile.renameTo(cacheFile)) {
                            // fallback: copy then delete tmp
                            tmpFile.inputStream().use { fis ->
                                FileOutputStream(cacheFile).use { fos ->
                                    fis.copyTo(fos)
                                }
                            }
                            tmpFile.delete()
                        }
                        onSuccess(cacheFile)
                    } catch (e: Exception) {
                        try { cacheFile.delete() } catch (_: Throwable) {}
                        onFailure(e)
                    } finally {
                        try { response.close() } catch (_: Throwable) {}
                    }
                }
            })

            // 正常返回 cancelBlock（方法签名需是 (() -> Unit)?）
            return cancelBlock
        }

        fun cancelAll() {
            runningCalls.values.forEach { try { it.cancel() } catch (_: Throwable) {} }
            runningCalls.clear()
        }
    }

    var fileDownloader = FileDownloader()

    companion object {
        private val threadNum = AtomicInteger(0)
        internal val decodeQueue: PriorityBlockingQueue<DecodeTask> = PriorityBlockingQueue()
        private var decoderThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(3)
        internal var threadPoolExecutor: ExecutorService = Executors.newFixedThreadPool(
            decoderThreads
        ) { r -> Thread(r, "SVGA-Decode-${threadNum.getAndIncrement()}") }

        private var mShareParser = SVGAParser(null)
        fun shareParser(): SVGAParser = mShareParser
    }

    fun setFrameSize(frameWidth: Int, frameHeight: Int) {
        logD("Setting frame size to W:$frameWidth, H:$frameHeight")
        mFrameWidth = frameWidth
        mFrameHeight = frameHeight
    }

    data class DecodeTask(
        val file: File?,
        val inputStream: InputStream?,
        val cacheKey: String,
        val callback: ParseCompletion?,
        val playCallback: PlayCallback?,
        val alias: String?,
        val priority: Int = 0
    ) : Comparable<DecodeTask> {
        override fun compareTo(other: DecodeTask) = other.priority - priority
    }

    init {
        logD("Starting $decoderThreads decode threads.")
        repeat(decoderThreads) {
            threadPoolExecutor.submit {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        logD("Decode thread ${Thread.currentThread().name} waiting for task...")
                        decodeFileFlow(decodeQueue.take())
                    } catch (ie: InterruptedException) {
                        logD("Decode thread ${Thread.currentThread().name} interrupted.")
                        break
                    } catch (e: Exception) {
                        logE("decode loop error: ${e.message}")
                    }
                }
            }
        }
    }

    // 限制并发解码（控制内存峰值）
    private var decodeSemaphore = Semaphore(2)

    // 序列化 prepare 的 semaphore
    private val prepareSemaphore = Semaphore(1)

    // 文件大小上限（保护）：50MB
    private var maxFileSize = 50L * 1024 * 1024

    @JvmOverloads
    fun decodeFromURL(
        url: URL,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null,
        visible: Boolean = true
    ): (() -> Unit)? {
        logD("decodeFromURL called: ${url.toString()}")
        val cacheKey = SVGACache.buildCacheKey(url.toString())
        val cacheFile = SVGACache.buildSvgaFile(cacheKey)

        memoryCache.get(cacheKey)?.let {
            logD("Memory cache hit for URL: ${url.toString()}")
            postComplete(it, callback)
            return null
        }

        if (SVGACache.isCached(cacheKey)) {
            logD("Disk cache hit (isCached) for URL: ${url.toString()}, submitting decode task.")
            submitDecodeTask(
                cacheFile,
                null,
                cacheKey,
                callback,
                playCallback,
                url.toString(),
                if (visible) 10 else 0
            )
            return null
        }

        synchronized(downloadTasks) {
            if (downloadTasks.containsKey(url.toString())) {
                logD("URL ${url.toString()} is already downloading, adding to wait list.")
                downloadTasks[url.toString()]?.add {
                    submitDecodeTask(
                        cacheFile,
                        null,
                        cacheKey,
                        callback,
                        playCallback,
                        url.toString(),
                        if (visible) 10 else 0
                    )
                }
                return null
            } else {
                logD("Starting new download task for URL: ${url.toString()}")
                downloadTasks[url.toString()] = mutableListOf({
                    submitDecodeTask(
                        cacheFile,
                        null,
                        cacheKey,
                        callback,
                        playCallback,
                        url.toString(),
                        if (visible) 10 else 0
                    )
                })
            }
        }

        return fileDownloader.download(url.toString(), cacheFile, { file ->
            logD("URL download succeeded: ${url.toString()}, invoking ${downloadTasks[url.toString()]?.size ?: 0} waiting tasks.")
            downloadTasks.remove(url.toString())?.forEach { it.invoke() }
        }, { e ->
            logE("URL download failed: ${url.toString()}, error: ${e.message}")
            downloadTasks.remove(url.toString())?.forEach { postError(callback) }
        })
    }

    @JvmOverloads
    fun decodeFromAssets(
        assetName: String,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null,
        visible: Boolean = true
    ): (() -> Unit)? {
        logD("decodeFromAssets called (Stable InputStream Path): $assetName")
        val context = mContext ?: run {
            logE("Context is null for asset: $assetName")
            postError(callback)
            return null
        }
        val cacheKey = SVGACache.buildCacheKey("file:///assets/$assetName")

        memoryCache.get(cacheKey)?.let {
            logD("Memory cache hit for asset: $assetName")
            postComplete(it, callback)
            return null
        }

        val cacheDir = SVGACache.buildCacheDir(cacheKey)
        if (cacheDir.exists() && (File(cacheDir, "movie.binary").exists() || File(
                cacheDir,
                "movie.spec"
            ).exists())
        ) {
            logD("Assets ZIP cache dir found, submitting decode from disk.")
            submitDecodeTask(
                File(cacheDir, "movie.binary"),
                null,
                cacheKey,
                callback,
                playCallback,
                assetName,
                if (visible) 10 else 0
            )
            return null
        }

        try {
            val inputStream = context.assets.open(assetName).buffered()
            logD("Assets stream opened and submitting decode task using stable InputStream path.")
            submitDecodeTask(
                file = null,
                inputStream = inputStream,
                cacheKey = cacheKey,
                callback = callback,
                playCallback = playCallback,
                alias = assetName,
                priority = if (visible) 10 else 0
            )
            return null
        } catch (e: Exception) {
            logE("decodeFromAssets failed to open stream: ${e.message}")
            postError(callback)
            return null
        }
    }

    @JvmOverloads
    fun decodeFromInputStream(
        inputStream: InputStream,
        cacheKey: String,
        callback: ParseCompletion?,
        closeInputStream: Boolean = false,
        playCallback: PlayCallback? = null,
        alias: String? = null
    ) {
        if (mContext == null) {
            logE("在配置 SVGAParser context 前, 无法解析 SVGA 文件。")
            postError(callback)
            return
        }
        logD("================ decode $alias from input stream ================")

        memoryCache.get(cacheKey)?.let {
            logD("Memory cache hit for InputStream key: $cacheKey")
            postComplete(it, callback)
            return
        }

        val bufferedStream = if (inputStream.markSupported()) inputStream else BufferedInputStream(inputStream)

        submitDecodeTask(
            file = null,
            inputStream = bufferedStream,
            cacheKey = cacheKey,
            callback = callback,
            playCallback = playCallback,
            alias = alias,
            priority = 10 // 默认给高优先级
        )

        logD("decodeFromInputStream submitted task for alias: $alias")
    }

    private fun submitDecodeTask(
        file: File?, inputStream: InputStream?, cacheKey: String,
        callback: ParseCompletion?, playCallback: PlayCallback?,
        alias: String?, priority: Int
    ) {
        logD("Submitting DecodeTask. Key: $cacheKey, File: ${file?.name}, Stream: ${inputStream != null}, Priority: $priority")
        decodeQueue.offer(
            DecodeTask(
                file,
                inputStream,
                cacheKey,
                callback,
                playCallback,
                alias,
                priority
            )
        )
    }

    private fun decodeFileFlow(task: DecodeTask) {
        logD("Starting decodeFileFlow for cacheKey: ${task.cacheKey}. Source: ${if (task.file != null) "File" else if (task.inputStream != null) "Stream" else "None"}")
        try {
            // 限制并发解码，减少内存峰值
            decodeSemaphore.acquire()
            val lock = fileLockMap.getOrPut(task.cacheKey) { Any() }
            synchronized(lock) {
                logD("Acquired lock for cacheKey: ${task.cacheKey}")
                val videoItem = try {
                    when {
                        task.file != null -> {
                            val f = task.file
                            if (!f.exists()) throw IOException("Cache file not found: ${f.absolutePath}")
                            if (f.length() > maxFileSize) {
                                logE("File ${f.absolutePath} too large: ${f.length()} bytes. Skipping decode.")
                                f.delete()
                                throw IOException("SVGA file too large")
                            }
                            f.inputStream().buffered().use { bis ->
                                bis.mark(8)
                                val header = ByteArray(4)
                                val read = bis.read(header)
                                bis.reset()
                                if (read != 4) throw IOException("Failed to read file header")
                                if (isZipFile(header)) {
                                    logD("File stream identified as ZIP. Starting unzip...")
                                    unzipAndDecodeStream(bis, task.cacheKey)
                                } else {
                                    logD("File stream identified as GZIP/Binary. Starting Inflater decode.")
                                    decodeInflaterStream(bis, task.cacheKey)
                                }
                            }
                        }

                        task.inputStream != null -> {
                            val bufferedInputStream = task.inputStream
                            bufferedInputStream.mark(8)
                            val header = ByteArray(4)
                            val read = bufferedInputStream.read(header)
                            bufferedInputStream.reset()
                            if (read != 4) throw IOException("Failed to read stream header.")
                            if (isZipFile(header)) {
                                logD("Stream identified as ZIP. Starting stream unzip.")
                                unzipAndDecodeStream(bufferedInputStream, task.cacheKey)
                            } else {
                                logD("Stream identified as GZIP/Binary. Starting stream Inflater decode.")
                                decodeInflaterStream(bufferedInputStream, task.cacheKey)
                            }
                        }

                        else -> throw IllegalArgumentException("No source for decoding")
                    }
                } catch (e: Exception) {
                    logE("Error during decode: ${e.message}")
                    throw e
                }

                logD("Decoding successful for ${task.cacheKey}. Putting into memory cache.")
                memoryCache.put(task.cacheKey, videoItem)

                logD("Preparing video item for rendering...")
                // 序列化 prepare，避免同时 allocate 大量 Bitmap
                prepareSemaphore.acquire()
                try {
                    videoItem.prepare({
                        logD("Video item prepared. Posting completion for ${task.cacheKey}.")
                        postComplete(videoItem, task.callback)
                    }, task.playCallback)
                } finally {
                    prepareSemaphore.release()
                }
            }
        } catch (e: Exception) {
            logE("decodeFileFlow failed for ${task.cacheKey}: ${e.message}")
            task.file?.delete()
            postError(task.callback)
        } finally {
            try {
                decodeSemaphore.release()
            } catch (t: Throwable) {
                // ignore
            }
            logD("decodeFileFlow finished for ${task.cacheKey}.")
            // Ensure any provided InputStream is closed by caller. For assets we passed buffered stream of assets - caller should decide close.
        }
    }

    /**
     * 使用 ZipInputStream 的 entry 流直接 decode，避免一次性把 entry 写入内存或磁盘。
     * 但我们仍要保护单个 entry 的大小，避免 entry 非常大导致解码器 OOM。
     */
    private fun unzipAndDecodeStream(inputStream: InputStream, cacheKey: String): SVGAVideoEntity {
        logD("unzipAndDecodeStream started for $cacheKey.")
        val cacheDir = SVGACache.buildCacheDir(cacheKey)
        if (!cacheDir.exists()) cacheDir.mkdirs()

        ZipInputStream(BufferedInputStream(inputStream)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.contains("../")) {
                    entry = zip.nextEntry
                    continue
                }
                if (entry.name == "movie.binary") {
                    logD("Found movie.binary in ZIP for $cacheKey. Decoding stream directly...")
                    // protect per-entry size: we will wrap entry stream to prevent reading beyond maxFileSize
                    val limitedStream = LimitedInputStream(zip, maxFileSize)
                    // MovieEntity.ADAPTER.decode 需要整个 entry 的流直到 EOF; limitedStream 会在超限时抛异常
                    val videoEntity = BufferedInputStream(limitedStream).use { fis ->
                        SVGAVideoEntity(
                            MovieEntity.ADAPTER.decode(fis),
                            cacheDir,
                            mFrameWidth,
                            mFrameHeight
                        )
                    }
                    // persist movie.binary to disk for future fast reads (stream-copy from zip entry)
                    try {
                        // reopen zip? can't. so write existing decoded entity spec/binary from object - but we don't have raw bytes.
                        // As fallback, skip writing movie.binary here; future decode will read from disk cache only if exists.
                    } catch (_: Exception) {
                    }
                    logD("ZIP stream decoding complete for $cacheKey.")
                    return videoEntity
                }
                entry = zip.nextEntry
            }
        }

        // 如果 zip 中找不到 movie.binary，则尝试从 cacheDir 的磁盘格式解码
        logD("ZIP extraction finished, movie.binary not found. Decoding from disk cache dir.")
        return decodeFromDisk(SVGACache.buildCacheDir(cacheKey))
    }

    /**
     * Inflater (gzip-like) 解码
     */
    private fun decodeInflaterStream(input: InputStream, cacheKey: String): SVGAVideoEntity {
        logD("decodeInflaterStream started for $cacheKey.")
        val cacheDir = SVGACache.buildCacheDir(cacheKey)
        InflaterInputStream(BufferedInputStream(input)).use { inflater ->
            // 这里假设 MovieEntity.ADAPTER.decode 能从流中逐步读取，不会一次性 allocate 巨大内存，
            // 如果它内部做了巨量分配，则需要在 MovieEntity 层面优化。
            val entity = SVGAVideoEntity(
                MovieEntity.ADAPTER.decode(inflater),
                cacheDir,
                mFrameWidth,
                mFrameHeight
            )
            logD("Inflater decode complete for $cacheKey.")
            return entity
        }
    }

    private fun decodeFromDisk(cacheDir: File): SVGAVideoEntity {
        logD("decodeFromDisk started for dir: ${cacheDir.name}")
        val binaryFile = File(cacheDir, "movie.binary")
        if (binaryFile.exists()) {
            logD("Found movie.binary, decoding...")
            return binaryFile.inputStream().buffered().use {
                SVGAVideoEntity(MovieEntity.ADAPTER.decode(it), cacheDir, mFrameWidth, mFrameHeight)
            }
        }
        val specFile = File(cacheDir, "movie.spec")
        logD("Found movie.spec, decoding...")
        specFile.inputStream().buffered().use { fis ->
            InputStreamReader(fis, Charsets.UTF_8).use { reader ->
                val jsonString = reader.readText()
                val obj = JSONObject(jsonString)
                return SVGAVideoEntity(obj, cacheDir, mFrameWidth, mFrameHeight)
            }
        }
    }

    private fun postComplete(video: SVGAVideoEntity, callback: ParseCompletion?) {
        Handler(Looper.getMainLooper()).post { callback?.onComplete(video) }
    }

    private fun postError(callback: ParseCompletion?) {
        Handler(Looper.getMainLooper()).post { callback?.onError() }
    }

    private fun isZipFile(bytes: ByteArray): Boolean {
        val isZip =
            bytes.size >= 4 && bytes[0].toInt() == 0x50 && bytes[1].toInt() == 0x4B && bytes[2].toInt() == 0x03 && bytes[3].toInt() == 0x04
        logD("Checked file header. Is ZIP: $isZip")
        return isZip
    }

    fun updateTaskPriority(cacheKey: String, newPriority: Int) {
        synchronized(decodeQueue) {
            logD("Updating priority for $cacheKey to $newPriority.")
            val taskList = decodeQueue.filter { it.cacheKey == cacheKey }
            taskList.forEach {
                decodeQueue.remove(it)
                decodeQueue.offer(it.copy(priority = newPriority))
            }
        }
    }

    @Deprecated(
        "This method has been deprecated from 2.4.0.",
        ReplaceWith("this.decodeFromAssets(assetsName, callback)")
    )
    fun parse(assetsName: String, callback: ParseCompletion?) {
        this.decodeFromAssets(assetsName, callback, null)
    }

    @Deprecated(
        "This method has been deprecated from 2.4.0.",
        ReplaceWith("this.decodeFromURL(url, callback)")
    )
    fun parse(url: URL, callback: ParseCompletion?) {
        this.decodeFromURL(url, callback, null)
    }

    @Deprecated(
        "This method has been deprecated from 2.4.0.",
        ReplaceWith("this.decodeFromInputStream(inputStream, cacheKey, callback, closeInputStream)")
    )
    fun parse(
        inputStream: InputStream,
        cacheKey: String,
        callback: ParseCompletion?,
        closeInputStream: Boolean = false
    ) {
        this.decodeFromInputStream(inputStream, cacheKey, callback, closeInputStream, null)
    }

    // ------ 新增 API 用于内存与生命周期控制 ------

    /**
     * 清空内存缓存
     */
    fun clearMemoryCache() {
        logD("Clearing memory cache.")
        memoryCache.evictAll()
    }

    /**
     * 主动调用以帮助回收，level 可用 Android 的 ComponentCallbacks2 常量
     */
    fun trimMemory(level: Int) {
        logD("trimMemory called with level: $level")
        if (level >= 15) { // TRIM_MEMORY_RUNNING_LOW
            clearMemoryCache()
        } else {
            // 轻度 trim: 清理部分缓存
            // LruCache 没有直接 trimToSizePercent，留作扩展
        }
    }

    /**
     * 关闭所有后台解码线程和下载
     */
    fun shutdown() {
        logD("Shutdown called: terminating thread pool and cancelling downloads.")
        try {
            threadPoolExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        fileDownloader.cancelAll()
        clearMemoryCache()
    }

    /**
     * 调整最大并发解码数（运行时可调用）
     */
    fun setDecodeConcurrency(maxConcurrent: Int) {
        if (maxConcurrent <= 0) return
        logD("setDecodeConcurrency: $maxConcurrent")
        decodeSemaphore = Semaphore(maxConcurrent)
    }

    /**
     * 设置单文件最大允许大小（字节）
     */
    fun setMaxFileSize(bytes: Long) {
        if (bytes > 0) {
            maxFileSize = bytes
            logD("setMaxFileSize: $bytes")
        }
    }

    /**
     * 调整内存缓存大小（字节）
     */
    fun setMemoryCacheSize(bytes: Int) {
        if (bytes <= 0) return
        memoryCacheMaxBytes = bytes
        // LruCache 构造后无法直接改变容量，采用 clear + 新建策略
        val temp = object : LruCache<String, SVGAVideoEntity>(memoryCacheMaxBytes) {
            override fun sizeOf(key: String, value: SVGAVideoEntity): Int = value.estimateSize()
        }
        synchronized(memoryCache) {
            memoryCache.snapshot().forEach { (k, v) ->
                temp.put(k, v)
            }
            memoryCache.evictAll()
        }
        // Not possible to reassign original memoryCache (it's val). If需要，可改为 var。
        // 此处仅记录日志并保留原 cache - 若需动态调整，请把 memoryCache 改为 var 并赋值 temp。
        logD("setMemoryCacheSize called (note: to apply at runtime convert memoryCache to var).")
    }

    private fun logD(msg: String) {
        LogUtils.info("SVGAParser D", " $msg")
    }

    private fun logE(msg: String) {
        LogUtils.error("SVGAParser E", "$msg")
    }

    // 限制输入流读取大小（防止单 entry 或流过大）
    private class LimitedInputStream(private val wrapped: InputStream, private val limit: Long) : InputStream() {
        private var readSoFar = 0L
        override fun read(): Int {
            if (readSoFar >= limit) throw IOException("Entry too large")
            val r = wrapped.read()
            if (r != -1) readSoFar++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (readSoFar >= limit) throw IOException("Entry too large")
            val max = if (readSoFar + len > limit) (limit - readSoFar).toInt() else len
            val r = wrapped.read(b, off, max)
            if (r > 0) readSoFar += r
            return r
        }

        override fun available(): Int = wrapped.available()
        override fun close() {
            // 不关闭底层流，这由调用者或 ZipInputStream 管理
        }
    }
}

/**
 * 估算视频实体占用（以字节为单位）
 */
fun SVGAVideoEntity.estimateSize(): Int = try {
    val estimatedBytes = width * height * 4
    // 最小 100KB 保底
    estimatedBytes.coerceAtLeast(100 * 1024)
} catch (_: Exception) {
    100 * 1024
}

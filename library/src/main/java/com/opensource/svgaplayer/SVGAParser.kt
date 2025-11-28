package com.opensource.svgaplayer

import android.R.attr.height
import android.R.attr.width
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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

class SVGAParser(context: Context?) {

    private var mContext = context?.applicationContext

    init {
        logD("SVGAParser Initializing...")
        mContext?.let {
            SVGACache.onCreate(it)
            logD("SVGA Cache Dir: ${it.cacheDir.absolutePath}") // 打印应用的默认缓存目录
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

    private val memoryCache = object : LruCache<String, SVGAVideoEntity>(30 * 1024 * 1024) {
        override fun sizeOf(key: String, value: SVGAVideoEntity): Int = value.estimateSize()
    }

    private val fileLockMap = ConcurrentHashMap<String, Any>()
    private val downloadTasks = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    class FileDownloader(maxConcurrent: Int = 3) {
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
        private val semaphore = Semaphore(maxConcurrent)

        fun download(
            url: String,
            cacheFile: File,
            onSuccess: (File) -> Unit,
            onFailure: (Exception) -> Unit
        ): (() -> Unit)? {
            var cancelled = false
            val cancelBlock = { cancelled = true }

            try {
                semaphore.acquire()
            } catch (e: InterruptedException) {
                return null
            }

            val request = Request.Builder().url(url).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    semaphore.release()
                    if (!cancelled) cacheFile.delete()
                    if (!cancelled) onFailure(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    semaphore.release()
                    if (cancelled) return
                    try {
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val tmpFile = File(cacheFile.absolutePath + ".tmp")
                        response.body?.byteStream()?.buffered()?.use { input ->
                            FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
                        }
                        tmpFile.renameTo(cacheFile)
                        onSuccess(cacheFile)
                    } catch (e: Exception) {
                        cacheFile.delete()
                        onFailure(e)
                    }
                }
            })
            return cancelBlock
        }
    }

    var fileDownloader = FileDownloader()

    companion object {
        private val threadNum = AtomicInteger(0)
        internal val decodeQueue: PriorityBlockingQueue<DecodeTask> = PriorityBlockingQueue()
        internal val threadPoolExecutor: ExecutorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
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
        logD("Starting ${Runtime.getRuntime().availableProcessors()} decode threads.")
        repeat(Runtime.getRuntime().availableProcessors()) {
            threadPoolExecutor.submit {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        logD("Decode thread ${Thread.currentThread().name} waiting for task...")
                        decodeFileFlow(decodeQueue.take())
                    } catch (_: InterruptedException) {
                        logD("Decode thread ${Thread.currentThread().name} interrupted.")
                        break
                    } catch (e: Exception) {
                        logE("decode loop error: ${e.message}")
                    }
                }
            }
        }
    }

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
            logD("Memory cache hit for URL: ${url.toString()}"); postComplete(
            it,
            callback
        ); return null
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

    /**
     * 【最终稳定方案】: 强制Assets走 InputStream 解码路径，绕过线程池启动问题和磁盘写入问题。
     */
    @JvmOverloads
    fun decodeFromAssets(
        assetName: String,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null,
        visible: Boolean = true
    ): (() -> Unit)? {
        logD("decodeFromAssets called (Stable InputStream Path): $assetName")
        val context = mContext ?: run {
            logE("Context is null for asset: $assetName"); postError(callback); return null
        }
        val cacheKey = SVGACache.buildCacheKey("file:///assets/$assetName")

        // 1. 内存缓存检查
        memoryCache.get(cacheKey)?.let {
            logD("Memory cache hit for asset: $assetName"); postComplete(it, callback); return null
        }

        // 2. 检查 ZIP 解压后的目录缓存 (Assets 文件的持久缓存)
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

        // 3. 核心逻辑：直接从 Assets 获取 InputStream 并提交解码任务。
        try {
            // 在调用线程（通常是主线程）打开流，然后提交给解码线程。
            // 使用 buffered() 包装，确保流支持 mark/reset，用于后续的内存优化。
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

    /**
     * 【整合方法】: 从 InputStream 解析 SVGA 文件，已优化为流式处理。
     * 原方法中的 readAsBytes 逻辑被移除，改为直接将流提交到高性能的 decodeFileFlow。
     */
    @JvmOverloads
    fun decodeFromInputStream(
        inputStream: InputStream,
        cacheKey: String,
        callback: ParseCompletion?,
        closeInputStream: Boolean = false, // 逻辑上，流会在 decodeFileFlow 内部的 use 块中关闭。
        playCallback: PlayCallback? = null,
        alias: String? = null
    ) {
        if (mContext == null) {
            logE("在配置 SVGAParser context 前, 无法解析 SVGA 文件。")
            return
        }
        logD("================ decode $alias from input stream ================")

        // 1. 内存缓存检查
        memoryCache.get(cacheKey)?.let {
            logD("Memory cache hit for InputStream key: $cacheKey"); postComplete(
            it,
            callback
        ); return
        }

        // 2. 核心逻辑：将 InputStream 封装并提交
        // 使用 buffered() 包装流，确保它支持 mark/reset，以启用流式预读和解码，实现低内存消耗。
        val bufferedStream =
            if (inputStream.markSupported()) inputStream else inputStream.buffered()

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

    /**
     * 【内存优化 M1】: 优化了 task.inputStream != null 分支，使用 mark/reset 避免 readBytes()
     */
    private fun decodeFileFlow(task: DecodeTask) {
        logD("Starting decodeFileFlow for cacheKey: ${task.cacheKey}. Source: ${if (task.file != null) "File" else if (task.inputStream != null) "Stream" else "None"}")
        try {
            val lock = fileLockMap.getOrPut(task.cacheKey) { Any() }
            synchronized(lock) {
                logD("Acquired lock for cacheKey: ${task.cacheKey}")

                val videoItem = when {
                    task.file != null -> {
                        // 1. 【文件源处理】: File 必须读入内存，因为 FileStream 不支持 reset()
                        logD("Decoding from File: ${task.file.absolutePath}. Reading bytes...")
                        val bytes = task.file.readBytes()
                        val bais = ByteArrayInputStream(bytes)

                        if (isZipFile(bytes)) {
                            logD("File identified as ZIP. Starting unzip...")
                            unzipAndDecodeStream(bais, task.cacheKey)
                        } else {
                            logD("File identified as GZIP/Binary. Deleting temp cache file and starting Inflater decode.")
                            task.file.delete()
                            decodeInflaterStream(bais, task.cacheKey)
                        }
                    }

                    task.inputStream != null -> {
                        // 2. 【InputStream 源处理】: 内存优化关键点
                        logD("Decoding from InputStream. Pre-reading header for type check (Memory Optimized)...")

                        // 确保流是 BufferedInputStream，用于 mark/reset
                        val bufferedInputStream = task.inputStream

                        val header = ByteArray(4)

                        if (!bufferedInputStream.markSupported()) {
                            // Fallback：如果流不支持 mark，则必须回退到 readBytes
                            logE("Stream does not support mark/reset. Falling back to readBytes.")
                            val bytes = bufferedInputStream.readBytes()
                            val bais = ByteArrayInputStream(bytes)
                            if (isZipFile(bytes)) {
                                unzipAndDecodeStream(bais, task.cacheKey)
                            } else {
                                decodeInflaterStream(bais, task.cacheKey)
                            }
                        } else {
                            bufferedInputStream.mark(4) // 标记流的起始位置
                            if (bufferedInputStream.read(header) != 4) {
                                throw IOException("Failed to read stream header.")
                            }
                            bufferedInputStream.reset() // 重置流到标记位置，实现流式解码

                            if (isZipFile(header)) {
                                logD("Stream identified as ZIP. Starting stream unzip.")
                                unzipAndDecodeStream(bufferedInputStream, task.cacheKey)
                            } else {
                                logD("Stream identified as GZIP/Binary. Starting stream Inflater decode.")
                                decodeInflaterStream(bufferedInputStream, task.cacheKey)
                            }
                        }
                    }

                    else -> throw IllegalArgumentException("No source for decoding")
                }
                logD("Decoding successful for ${task.cacheKey}. Putting into memory cache.")
                memoryCache.put(task.cacheKey, videoItem)

                logD("Preparing video item for rendering...")
                videoItem.prepare({
                    logD("Video item prepared. Posting completion for ${task.cacheKey}.")
                    postComplete(videoItem, task.callback)
                }, task.playCallback)
            }
        } catch (e: Exception) {
            logE("decodeFileFlow failed for ${task.cacheKey}: ${e.message}")
            task.file?.delete()
            postError(task.callback)
        } finally {
            // 注意：bufferedInputStream 会在 unzipAndDecodeStream 或 decodeInflaterStream 内部的 use 块中被关闭
            logD("Input stream closed (handled internally) for ${task.cacheKey}.")
        }
    }

    private fun unzipAndDecodeStream(inputStream: InputStream, cacheKey: String): SVGAVideoEntity {
        logD("unzipAndDecodeStream started for $cacheKey.")
        val cacheDir = SVGACache.buildCacheDir(cacheKey)
        if (!cacheDir.exists()) cacheDir.mkdirs()

        // 这里的 BufferedInputStream 是为了确保 ZipInputStream 有足够的缓冲
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.contains("../")) {
                    entry = zip.nextEntry; continue
                }
                if (entry.name == "movie.binary") {
                    logD("Found movie.binary in ZIP for $cacheKey. Extracting...")
                    val tempBinary = File(cacheDir, "movie.binary.tmp")
                    FileOutputStream(tempBinary).use { fos ->
                        zip.copyTo(fos)
                    }
                    tempBinary.inputStream().buffered().use { fis ->
                        val videoEntity = SVGAVideoEntity(
                            MovieEntity.ADAPTER.decode(fis),
                            cacheDir,
                            mFrameWidth,
                            mFrameHeight
                        )
                        tempBinary.renameTo(File(cacheDir, "movie.binary"))
                        logD("ZIP extraction and decoding complete for $cacheKey.")
                        return videoEntity
                    }
                }
                entry = zip.nextEntry
            }
        }
        logD("ZIP extraction finished, movie.binary not found. Decoding from disk cache dir.")
        return decodeFromDisk(SVGACache.buildCacheDir(cacheKey))
    }

    private fun decodeInflaterStream(input: InputStream, cacheKey: String): SVGAVideoEntity {
        logD("decodeInflaterStream started for $cacheKey.")
        val cacheDir = SVGACache.buildCacheDir(cacheKey)
        InflaterInputStream(input).buffered().use { inflater ->
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

        // 优化 P1: 流式读取 JSON
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
        // Zip 文件头: PK\x03\x04 (0x50 0x4B 0x03 0x04)
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

    /**
     * @deprecated from 2.4.0
     */
    @Deprecated(
        "This method has been deprecated from 2.4.0.",
        ReplaceWith("this.decodeFromAssets(assetsName, callback)")
    )
    fun parse(assetsName: String, callback: ParseCompletion?) {
        this.decodeFromAssets(assetsName, callback, null)
    }

    /**
     * @deprecated from 2.4.0
     */
    @Deprecated(
        "This method has been deprecated from 2.4.0.",
        ReplaceWith("this.decodeFromURL(url, callback)")
    )
    fun parse(url: URL, callback: ParseCompletion?) {
        this.decodeFromURL(url, callback, null)
    }

    /**
     * @deprecated from 2.4.0
     */
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

    // =======================================================================
    // 移除的原有辅助函数，以流式处理方式取代其功能
    // =======================================================================
    /*
    private fun readAsBytes(inputStream: InputStream): ByteArray? { ... }
    private fun inflate(byteArray: ByteArray): ByteArray? { ... }
    private fun invokeCompleteCallback( ... ) { ... } // 被 postComplete 取代
    private fun invokeErrorCallback( ... ) { ... }   // 被 postError 取代
    // decodeFromCacheKey 逻辑被移动和优化到 decodeFromDisk 中
    */

    private fun logD(msg: String) {
        LogUtils.info("SVGAParser D", " $msg")
    }

    private fun logE(msg: String) {
        LogUtils.error("SVGAParser E", "$msg")
    }
}

// 优化 SVGAVideoEntity 内存估算
fun SVGAVideoEntity.estimateSize(): Int = try {
    // 修正公式：只估算一帧的渲染大小，或一个合理的图像资产大小。
    // 假设最大的图像资产接近一帧的大小
    val estimatedBytes = width * height * 4

    // 确保最小值为 100KB，避免大批小动画占用 LruCache 预算过多，同时避免公式溢出。
    estimatedBytes.coerceAtLeast(100 * 1024)

} catch (_: Exception) {
    100 * 1024 // 发生异常时，默认估算 100KB
}
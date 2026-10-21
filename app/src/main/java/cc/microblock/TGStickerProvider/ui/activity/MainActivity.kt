@file:Suppress("SetTextI18n", "Deprecation")

package cc.microblock.TGStickerProvider.ui.activity

import android.app.AlertDialog
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.MutableLiveData
import cc.microblock.TGStickerProvider.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.hook.log.YLog
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread


data class StickerState(var highQuality: Int, var lowQuality: Int, var all: Int)
data class PreviewInfo(var type: String, var url: String)
data class OperationProgress(
    val title: String,
    val detail: String = "",
    val fraction: Float? = null,
    val expressive: Boolean = false
)
enum class SyncQuality(val suffix: String) { LOW("_low"), HIGH("_high") }
data class DeleteConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val iconRes: Int,
    val action: () -> Unit
)
data class StickerInfo(
    val name: String,
    val id: String,
    val hash: String,
    val remoteState: StickerState,
    val syncedState: StickerState,
    val all: Int,
    val type: String,
    val preview: PreviewInfo
)

fun effectiveSyncQuality(info: StickerInfo, selected: SyncQuality?): SyncQuality = when {
    info.remoteState.highQuality > 0 && info.remoteState.lowQuality == 0 -> SyncQuality.HIGH
    info.remoteState.lowQuality > 0 && info.remoteState.highQuality == 0 -> SyncQuality.LOW
    else -> selected ?: if (info.remoteState.highQuality > 0) SyncQuality.HIGH else SyncQuality.LOW
}

class MainActivity : AppCompatActivity() {

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 先判断有没有权限
            if (Environment.isExternalStorageManager()) {
                onGainedPermission()
                return
            }
            Toast.makeText(
                this,
                "请授予插件完全文件访问权限，以同步表情包~",
                Toast.LENGTH_LONG
            ).show()
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:" + this@MainActivity.packageName)
            }, 2339)
        } else {
            loadStickerList()
        }
    }

    private fun onGainedPermission() {
        Toast.makeText(
            this,
            "权限获取成功，可以同步表情包啦~",
            Toast.LENGTH_LONG
        ).show()

        loadStickerList()
    }

    private fun loadStickerList() {
        operationProgress = OperationProgress("正在加载表情包", "扫描已导出的表情包")
        thread {
            val result = runCatching { updateStickerList() }
            runOnUiThread {
                operationProgress = null
                result.onFailure {
                    YLog.error("Error while loading stickers", it)
                    Toast.makeText(this, "加载表情包失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncStickerPack(
        info: StickerInfo,
        quality: SyncQuality,
        finished: () -> Unit,
        error: (Int, String) -> Unit,
        packIndex: Int = 0,
        packCount: Int = 1
    ) {
        val act = this
        val s = info
        act.runOnUiThread {
            operationProgress = OperationProgress("正在同步", s.name, packIndex.toFloat() / packCount, expressive = true)
            thread(true) {
                try {
                    val remoteFolder = "$destDataPath/tgSync_${s.id}"
                    val syncedFolder = "$realDataPath/tgSync_${s.id}"
                    File(syncedFolder).mkdirs()

                    if (!File(nomediaPath2).exists()) {
                        File(nomediaPath2).createNewFile()
                    }

                    val stickerPositions = File(stickerDataPath, s.hash).readLines().drop(4)
                        .mapIndexedNotNull { index, line ->
                            line.trim().toLongOrNull()?.let { it.toString() to index }
                        }.toMap()
                    val remoteFileList = File(remoteFolder).listFiles().orEmpty()
                        .filter { it.isFile && it.extension.lowercase() in setOf("webp", "webm") }
                        .groupBy { it.nameWithoutExtension.substringBefore('_') }
                        .values.map { files ->
                            val preferred = files.filter { it.nameWithoutExtension.endsWith(quality.suffix) }
                            val fallback = files.filter {
                                it.nameWithoutExtension.endsWith(
                                    if (quality == SyncQuality.HIGH) SyncQuality.LOW.suffix else SyncQuality.HIGH.suffix
                                )
                            }
                            (preferred.ifEmpty { fallback }.ifEmpty { files })
                                .maxBy { if (it.extension.equals("webm", ignoreCase = true)) 1 else 0 }
                        }.sortedWith(compareBy { remoteFile ->
                            stickerPositions[remoteFile.nameWithoutExtension.substringBefore('_')] ?: Int.MAX_VALUE
                        })
                    if (remoteFileList.isEmpty()) {
                        error(1, "${s.name}: 没有可同步的表情文件")
                        return@thread
                    }

                    val syncedDirectory = File(syncedFolder)
                    val stagingDirectory = File(realDataPath, ".tgsp-stage-${s.id}")
                    restoreStagedBackups(stagingDirectory, syncedDirectory)
                    if (stagingDirectory.exists() && !stagingDirectory.deleteRecursively()) {
                        throw IOException("无法清理上次同步的临时文件")
                    }
                    if (!stagingDirectory.mkdirs()) throw IOException("无法创建同步临时目录")

                    val completed = AtomicInteger(0)
                    var displayed = 0
                    val workers = Executors.newFixedThreadPool(32)
                    val failures = try {
                        val conversionResults = try {
                            workers.invokeAll(remoteFileList.map { remoteFile ->
                                Callable {
                                    val failure = syncStickerFile(remoteFile, stagingDirectory)
                                    val done = completed.incrementAndGet()
                                    act.runOnUiThread {
                                        if (done > displayed) {
                                            displayed = done
                                            operationProgress = OperationProgress(
                                                "正在同步",
                                                "${s.name} · $done/${remoteFileList.size}",
                                                (packIndex + done.toFloat() / remoteFileList.size) / packCount,
                                                expressive = true
                                            )
                                        }
                                    }
                                    failure
                                }
                            }).map { it.get() }
                        } finally {
                            workers.shutdownNow()
                        }
                        act.runOnUiThread {
                            operationProgress = OperationProgress("正在整理贴纸", s.name, (packIndex + 1f) / packCount, expressive = true)
                        }
                        publishStickersInOrder(
                            remoteFileList, conversionResults, stickerPositions,
                            stagingDirectory, syncedDirectory
                        )
                    } finally {
                        restoreStagedBackups(stagingDirectory, syncedDirectory)
                        stagingDirectory.deleteRecursively()
                    }
                    act.runOnUiThread {
                        operationProgress = OperationProgress(
                            "正在更新列表", s.name, (packIndex + 1f) / packCount,
                            expressive = true
                        )
                    }
                    act.updateStickerList()

                    if (failures.isNotEmpty()) {
                        error(failures.size, failures.joinToString(separator = "\n"))
                    } else {
                        finished();
                    }
                } catch (exception: Exception) {
                    YLog.error("Error while syncing ${s.name}", exception)
                    error(1, exception.message ?: "同步失败")
                }
            }
        }
    }

    private fun stickerId(file: File): String = file.nameWithoutExtension.substringBefore('_')

    private fun isSyncedSticker(file: File): Boolean =
        file.isFile && file.extension.lowercase() in setOf("png", "gif", "webp")

    private fun restoreStagedBackups(stagingDirectory: File, syncedDirectory: File) {
        val backupDirectory = File(stagingDirectory, "previous")
        val currentIds = syncedDirectory.listFiles().orEmpty()
            .filter(::isSyncedSticker).map(::stickerId).toSet()
        backupDirectory.listFiles().orEmpty().filter(::isSyncedSticker).sortedBy { it.name }
            .filter { stickerId(it) !in currentIds }
            .forEach { old ->
                Files.move(old.toPath(), File(syncedDirectory, old.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
    }

    private fun publishStickersInOrder(
        remoteFiles: List<File>,
        conversionResults: List<String?>,
        stickerPositions: Map<String, Int>,
        stagingDirectory: File,
        syncedDirectory: File
    ): List<String> {
        val backupDirectory = File(stagingDirectory, "previous")
        if (!backupDirectory.mkdirs()) throw IOException("无法创建贴纸备份目录")
        syncedDirectory.listFiles().orEmpty().filter(::isSyncedSticker).forEach { old ->
            Files.move(old.toPath(), File(backupDirectory, old.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        val remoteById = remoteFiles.mapIndexed { index, file ->
            stickerId(file) to (file to conversionResults[index])
        }.toMap()
        val backupsById = backupDirectory.listFiles().orEmpty().filter(::isSyncedSticker).groupBy(::stickerId)
        val orderedIds = (remoteFiles.map(::stickerId) + backupsById.keys).distinct()
            .withIndex()
            .sortedWith(compareBy<IndexedValue<String>> { stickerPositions[it.value] ?: Int.MAX_VALUE }
                .thenBy { it.index })
            .map { it.value }
        val failures = mutableListOf<String>()
        val firstTimestamp = System.currentTimeMillis() - orderedIds.size * 1000L

        orderedIds.forEachIndexed { index, id ->
            val selected = remoteById[id]
            var published = false
            if (selected != null) {
                val (remoteFile, conversionFailure) = selected
                if (conversionFailure == null) {
                    val staged = File(stagingDirectory, "${remoteFile.nameWithoutExtension}.png")
                    val output = File(syncedDirectory, staged.name)
                    try {
                        if (!staged.isFile) throw IOException("转换结果不存在")
                        Files.move(staged.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        output.setLastModified(firstTimestamp + index * 1000L)
                        published = true
                    } catch (e: Exception) {
                        failures.add("$id: ${e.message ?: "保存失败"}")
                    }
                } else {
                    failures.add(conversionFailure)
                }
            }
            if (!published) {
                backupsById[id].orEmpty().forEach { old ->
                    val restored = File(syncedDirectory, old.name)
                    Files.move(old.toPath(), restored.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    restored.setLastModified(firstTimestamp + index * 1000L)
                }
            }
        }
        return failures
    }

    private fun syncStickerFile(remoteFile: File, syncedFolder: File): String? {
        val baseName = remoteFile.nameWithoutExtension
        val id = baseName.substringBefore('_')
        val output = File(syncedFolder, "$baseName.png")
        return try {
            when (remoteFile.extension.lowercase()) {
                "webp" -> {
                    val source = ImageDecoder.createSource(remoteFile)
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    try {
                        output.outputStream().buffered().use {
                            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) {
                                throw IOException("PNG 编码失败")
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                "webm" -> {
                    val temporary = File(syncedFolder, "$baseName.png.tmp")
                    try {
                        val session = FFmpegKit.execute(
                            "-y -c:v libvpx-vp9 -i \"${remoteFile.absolutePath}\" -vsync 0 -pix_fmt rgba " +
                                "-c:v apng -f apng -plays 0 \"${temporary.absolutePath}\""
                        )
                        if (!session.returnCode.isValueSuccess || temporary.length() == 0L) {
                            throw IOException("APNG 转换失败，返回码 ${session.returnCode}")
                        }
                        Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        temporary.delete()
                    }
                }
                else -> throw IOException("不支持的格式：${remoteFile.extension}")
            }
            if (output.exists()) {
                syncedFolder.listFiles().orEmpty()
                    .filter {
                        it != output && it.nameWithoutExtension.substringBefore('_') == id &&
                            it.extension in setOf("png", "gif", "webp")
                    }
                    .forEach { it.delete() }
            }
            null
        } catch (e: Exception) {
            YLog.error("Error while syncing $baseName", e)
            "$id: ${e.message ?: "转换失败"}"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2339) {
            if (Environment.isExternalStorageManager()) {
                onGainedPermission()
                return
            }
            Toast.makeText(
                this,
                "请授予插件完全文件访问权限，以同步表情包~",
                Toast.LENGTH_LONG
            ).show()
            requestPermission()
        }

    }

    private var filter by mutableStateOf("")
    private var moduleActive by mutableStateOf(false)
    private var visibleStickers by mutableStateOf<List<StickerInfo>>(emptyList())
    private var operationProgress by mutableStateOf<OperationProgress?>(null)
    private var refreshing by mutableStateOf(false)
    private var deleteConfirmation by mutableStateOf<DeleteConfirmation?>(null)
    private val qualitySelections = mutableStateMapOf<String, SyncQuality>()
    private val qualityPreferences by lazy { getSharedPreferences("sync_quality", MODE_PRIVATE) }

    private fun selectedQuality(info: StickerInfo): SyncQuality =
        effectiveSyncQuality(info, qualitySelections[info.id])

    private fun changeQuality(info: StickerInfo, quality: SyncQuality) {
        qualitySelections[info.id] = quality
        qualityPreferences.edit().putString(info.id, quality.name).apply()
    }

    private fun onFilterChanged(list: List<StickerInfo>? = stickerList.value) {
        list.orEmpty().forEach { info ->
            if (!qualitySelections.containsKey(info.id)) {
                qualityPreferences.getString(info.id, null)?.let { saved ->
                    runCatching { SyncQuality.valueOf(saved) }.getOrNull()?.let {
                        qualitySelections[info.id] = it
                    }
                }
            }
        }
        visibleStickers = list.orEmpty().filter {
            it.name.contains(filter, ignoreCase = true) ||
                it.id.contains(filter, ignoreCase = true) ||
                it.type.contains(filter, ignoreCase = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(transparent, transparent),
            navigationBarStyle = SystemBarStyle.auto(transparent, transparent)
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        supportActionBar?.hide()
        stickerList.observe(this) { onFilterChanged(it) }
        refreshModuleStatus()
        setContent {
            StickerProviderScreen(
                moduleActive = moduleActive,
                version = getString(R.string.module_version, BuildConfig.VERSION_NAME),
                progress = operationProgress,
                refreshing = refreshing,
                deleteConfirmation = deleteConfirmation,
                qualitySelections = qualitySelections,
                stickers = visibleStickers,
                search = filter,
                onSearchChange = { filter = it; onFilterChanged() },
                onRefresh = ::refreshStickers,
                onSyncAll = ::syncAllStickers,
                onReset = ::resetCache,
                onSync = ::syncOneSticker,
                onQualityChange = ::changeQuality,
                onDeleteSynced = ::deleteSynced,
                onDeleteExported = ::deleteExported,
                onConfirmDelete = ::performConfirmedDelete,
                onDismissDelete = { deleteConfirmation = null }
            )
        }
        requestPermission()
    }

    private fun resetCache() {
        confirmDelete(
            "清除表情包导出缓存",
            "将清除已导出的表情包缓存，已同步的文件不会受影响。",
            confirmLabel = "清除",
            iconRes = R.drawable.ic_archive
        ) {
            File(exposedPath).deleteRecursively()
            stickerList.postValue(emptyList())
        }
    }

    private fun refreshStickers() {
        if (refreshing) return
        refreshing = true
        thread {
            val result = runCatching { updateStickerList() }
            runOnUiThread {
                refreshing = false
                result.onFailure { YLog.error("Error while refreshing stickers", it) }
                Toast.makeText(this, if (result.isSuccess) "表情包列表已刷新" else "刷新失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncOneSticker(info: StickerInfo) {
        syncStickerPack(info, selectedQuality(info), {
            runOnUiThread {
                operationProgress = null
                Toast.makeText(this, "同步完成", Toast.LENGTH_SHORT).show()
            }
        }, { count, reasons ->
            runOnUiThread {
                operationProgress = null
                showSyncErrors(count, reasons)
            }
        })
    }

    private fun syncAllStickers() {
        val items = stickerList.value.orEmpty().filter { it.type == "unknown" || it.type == "image/webp" || it.type == "video/webm" }
        if (items.isEmpty()) {
            Toast.makeText(this, "没有可同步的表情包", Toast.LENGTH_SHORT).show()
            return
        }
        var failCount = 0
        val reasons = StringBuilder()
        fun next(index: Int) {
            if (index == items.size) {
                runOnUiThread {
                    operationProgress = null
                    if (failCount > 0) showSyncErrors(failCount, reasons.toString())
                    else Toast.makeText(this, "同步完成", Toast.LENGTH_SHORT).show()
                }
                return
            }
            syncStickerPack(items[index], selectedQuality(items[index]), { next(index + 1) }, { count, detail ->
                failCount += count
                reasons.append(detail)
                next(index + 1)
            }, packIndex = index, packCount = items.size)
        }
        next(0)
    }

    private fun showSyncErrors(count: Int, reasons: String) {
        AlertDialog.Builder(this)
            .setTitle("有 $count 个表情同步失败")
            .setMessage(reasons)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun deleteSynced(info: StickerInfo) {
        confirmDelete(
            "删除已同步的表情包 ${info.name}",
            "这将删除 ${info.syncedState.all} 个已同步的表情文件。",
            confirmLabel = "删除",
            iconRes = R.drawable.ic_delete
        ) { File("$realDataPath/tgSync_${info.id}").deleteRecursively() }
    }

    private fun deleteExported(info: StickerInfo) {
        confirmDelete(
            "删除导出缓存 ${info.name}",
            "这将删除已缓存的表情包，不影响已同步的文件。",
            confirmLabel = "清除",
            iconRes = R.drawable.ic_archive
        ) {
            File("$destDataPath/tgSync_${info.id}").deleteRecursively()
            File("$stickerDataPath/${info.hash}").delete()
        }
    }

    private fun confirmDelete(
        title: String,
        message: String,
        confirmLabel: String,
        iconRes: Int,
        action: () -> Unit
    ) {
        deleteConfirmation = DeleteConfirmation(title, message, confirmLabel, iconRes, action)
    }

    private fun performConfirmedDelete() {
        val confirmation = deleteConfirmation ?: return
        deleteConfirmation = null
        operationProgress = OperationProgress("正在删除", confirmation.title)
        thread {
            val result = runCatching {
                confirmation.action()
                updateStickerList()
            }
            runOnUiThread {
                operationProgress = null
                result.onFailure {
                    YLog.error("Error while deleting stickers", it)
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshModuleStatus()
    }

    private val stickerList by lazy {
        MutableLiveData<List<StickerInfo>>(listOf())
    }

    fun updateStickerList() {
        if (!File(tgspDataPath).exists()) {
            this.stickerList.postValue(listOf())
            return
        }

        val ignoreIds = try {
            File("$exposedPath/ignore.txt").run {
                if (exists())
                    readLines().filterNot { it.isEmpty() || it.startsWith("#") }
                        .map { it.substringBefore('#').trim() }
                        .toSet()
                else {
                    writeText(
                        """# write the ignored sticker IDs here
                            |# separate with line breaks
                        """.trimMargin()
                    )
                    emptySet()
                }
            }
        } catch (e: Exception) {
            YLog.error("Error while reading ignore.txt", e)
            emptySet()
        }
        YLog.info("ignoreIds: [${ignoreIds.joinToString { "`$it`" }}]")

        val stickerList = mutableListOf<StickerInfo>()

        for (file in File(stickerDataPath).listFiles().orEmpty()) {
            try {
                val hash = file.name
                if (!file.name.endsWith(".stickerData.txt.jpg")) continue
                val lines = File(file.absolutePath).readLines()
                val id = lines[0]
                if (id in ignoreIds) continue
                val name = lines[1]
                val allSize = lines[2].toInt()
                val type = if(lines.size > 3) lines[3] else "unknown"

                if (stickerList.any { it.hash == hash }) continue

                val remoteState = StickerState(0, 0, 0)
                val syncedState = StickerState(0, 0, 0)

                File("$destDataPath/tgSync_$id").listFiles()?.forEach {
                    if (it.name.endsWith("_low.webp")) {
                        remoteState.lowQuality++
                    } else if (it.name.endsWith("_high.webp") || it.name.endsWith("_high.webm")) {
                        remoteState.highQuality++
                    }

                    remoteState.all++
                }

                File("$realDataPath/tgSync_${id}").listFiles()?.forEach {
                    if (it.name.contains("_low.")) {
                        syncedState.lowQuality++
                    } else if (it.name.contains("_high.")) {
                        syncedState.highQuality++
                    }

                    syncedState.all++
                }

                val files = File("$destDataPath/tgSync_${id}").listFiles()?: emptyArray();
                val preview = if (files.isNotEmpty()) {
                    val it = files[0]
                    if (it.extension == "webp") {
                        PreviewInfo("webp", it.absolutePath)
                    } else if(it.extension == "webm") {
                        PreviewInfo("webm", it.absolutePath)
                    } else
                    {
                        PreviewInfo("unknown", "")
                    }
                } else {
                    PreviewInfo("unknown", "")
                }

                stickerList.add(StickerInfo(name, id, hash, remoteState, syncedState, allSize, type, preview))
            } catch (e: Exception) {
                YLog.error("Error while parsing ${file.name}", e)
            }
        }

        // resort by remoteState.all
        stickerList.sortByDescending { it.remoteState.all }

        this.stickerList.postValue(stickerList)
    }

    /**
     * Refresh module status
     *
     * 刷新模块状态
     */
    private fun refreshModuleStatus() {
        moduleActive = YukiHookAPI.Status.isModuleActive
    }
}

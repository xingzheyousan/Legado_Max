package io.legado.app.help.webView

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import java.io.File

/**
 * WebView 文件上传（`<input type="file">`）统一处理。
 *
 * 浏览器 [io.legado.app.ui.browser.WebViewActivity] 与订阅源阅读页
 * [io.legado.app.ui.rss.read.ReadRssActivity] 共用同一份实现：
 * 单 URL 订阅源（RssSource.singleUrl）也是直接把网页加载进 WebView，
 * 之前阅读页的 WebChromeClient 没有 onShowFileChooser，点击上传没反应。
 *
 * 注意：必须在 Activity 构造期（字段初始化）创建实例，
 * registerForActivityResult 不允许在 STARTED 之后注册。
 */
class WebFileChooserHelper(private val activity: ComponentActivity) {

    /** 网页 `<input type="file">` 触发后，等待回填选择结果的回调 */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** `<input capture>` 拍照上传的临时文件 Uri */
    private var takePictureUri: Uri? = null

    // 文件选择器：自定义 Contract 支持 `<input multiple>` 多选，
    // 多个 accept 类型通过 EXTRA_MIME_TYPES 传递
    // （GetContent 只支持单个 mime，多个 join 用逗号拼接会导致选择器空白甚至抛异常）
    private val uploadFile = activity.registerForActivityResult(object :
        ActivityResultContract<Array<String>, Array<Uri>?>() {
        override fun createIntent(context: Context, input: Array<String>): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                if (input.size > 1) {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, input)
                } else {
                    type = input.firstOrNull() ?: "*/*"
                }
                // 允许多选
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Array<Uri>? {
            if (resultCode != Activity.RESULT_OK) return null
            return intent?.clipData?.let { clip ->
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            } ?: intent?.data?.let { arrayOf(it) }
        }
    }) { uris ->
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    // 拍照上传：走 TakePicture 拿到图片 Uri 后回填给 filePathCallback
    private val takePicture = activity.registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            // 拍照成功：只回传 Uri，让网页自行读取。此文件不能删！
            // （若此时删除，网页还没读到文件内容，上传必失败）
            takePictureUri?.let { filePathCallback?.onReceiveValue(arrayOf(it)) }
            takePictureUri = null
            // 回传完成后清掉回调引用，与取消分支对称
            filePathCallback = null
        } else {
            // 用户取消/失败：回填 null 让网页结束等待，临时文件可以删
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            deleteCaptureFile()
        }
    }

    /**
     * 网页 `<input type="file">` 被点击时由 WebChromeClient.onShowFileChooser 调用
     *
     * @return true 表示已接管；callback 为 null 时返回 false 让 WebView 走默认行为
     */
    fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>?,
        params: FileChooserParams?
    ): Boolean {
        // 回调为 null 时不要拦截，让 WebView 走默认行为
        val target = callback ?: return false
        // 取消上一次可能未完成的回调，防止页面残留导致泄漏
        filePathCallback?.onReceiveValue(null)
        filePathCallback = target

        // 网页声明 `<input capture>` 时优先走拍照；TakePicture 需要预先给一个可写入的 FileProvider Uri
        if (params?.isCaptureEnabled == true) {
            val uri = createImageUri()
            if (uri != null) {
                takePictureUri = uri
                if (takePicture.launchSafely(uri)) return true
                // 设备没有相机应用：删掉临时文件，回退到文件选择器
                deleteCaptureFile()
            }
        }

        // 解析 accept 类型；多个类型用数组传给 EXTRA_MIME_TYPES，避免逗号拼接的坑
        if (!uploadFile.launchSafely(resolveMimeTypes(params?.acceptTypes))) {
            // 没有任何可用应用接管：回填 null 让网页结束等待，否则页面会一直挂着
            filePathCallback = null
            target.onReceiveValue(null)
        }
        return true
    }

    /**
     * 把网页 accept 的值转成合法 MIME 类型。
     *
     * accept 里可能是扩展名（`.pdf`）、通配符（`image/*`）、空串，甚至混合，
     * 直接塞进 Intent 会得到非法 type，ACTION_OPEN_DOCUMENT 找不到 Activity
     * 就抛 ActivityNotFoundException（`.pdf` 崩溃日志见 2026-09-18）。
     * 无法识别的项直接丢弃；全部不可用则回退 `*/*`（宁可选全部，也不要崩）。
     */
    private fun resolveMimeTypes(acceptTypes: Array<String>?): Array<String> {
        val result = linkedSetOf<String>()
        acceptTypes.orEmpty().forEach { raw ->
            val item = raw.trim().lowercase()
            when {
                item.isEmpty() -> Unit
                // 通配符直接放行全部
                item == "*/*" -> return arrayOf("*/*")
                // 已经是 MIME 类型（含 image/*、video/*）
                item.contains("/") -> result.add(item)
                // 扩展名：.pdf / pdf / .tar.gz
                else -> {
                    val ext = item.removePrefix(".")
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                        ?: MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(ext.substringAfterLast('.'))
                    mime?.let { result.add(it) }
                }
            }
        }
        return result.toTypedArray().takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
    }

    /**
     * 启动系统选择器/相机。
     *
     * 该回调由 WebView native 层直调，任何异常都会一路抛到主线程 Looper 直接崩掉 App
     * （如 accept 非法 MIME 时的 ActivityNotFoundException），所以这里必须兜底，
     * 失败返回 false 由调用方回填 null 结束网页等待。
     */
    private fun <I> ActivityResultLauncher<I>.launchSafely(input: I): Boolean {
        return try {
            launch(input)
            true
        } catch (e: Exception) {
            AppLog.put("启动系统文件选择器失败", e)
            false
        }
    }

    /**
     * Activity 销毁时必须调用：回填 null 并清掉引用，否则文件选择回调一直挂着导致 WebView 等待/泄漏。
     * 同时兜底清理拍照临时目录。
     */
    fun onDestroy() {
        if (filePathCallback != null) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
        try {
            File(activity.cacheDir, CAPTURE_DIR).deleteRecursively()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 为 `<input capture>` 拍照上传创建图片临时文件，并返回 FileProvider Uri。
     * 缓存在 cacheDir 下，避免占用外部存储。
     */
    private fun createImageUri(): Uri? {
        return try {
            val dir = File(activity.cacheDir, CAPTURE_DIR).apply { mkdirs() }
            val file = File.createTempFile("capture_", ".jpg", dir)
            FileProvider.getUriForFile(activity, AppConst.authority, file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // 删除拍照临时文件。仅用于取消/失败场景；成功场景的文件交由 onDestroy 兜底清理，
    // 避免网页还没读完就被删。FileProvider 返回的都是 content://，无需判 file scheme。
    private fun deleteCaptureFile() {
        takePictureUri?.let { uri ->
            try {
                activity.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        takePictureUri = null
    }

    companion object {
        private const val CAPTURE_DIR = "web_capture"
    }

}

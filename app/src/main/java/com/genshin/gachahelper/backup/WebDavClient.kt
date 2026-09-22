package com.genshin.gachahelper.backup

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 操作结果
 */
sealed class WebDavResult {
    data class Success(val message: String) : WebDavResult()
    data class Failure(val message: String) : WebDavResult()
}

/**
 * 极简 WebDAV 客户端（2026-09-21 新增）
 *
 * 只实现备份所需的最小动作集，基于项目已有依赖 OkHttp 4.12：
 * - [testConnection]：PROPFIND Depth:0 探测连通性与鉴权
 * - [upload]：按需逐级 MKCOL 建目录 + PUT 上传文件
 *
 * 认证方式：HTTP Basic（Nextcloud / 坚果云 / 群晖 / Alist 等均支持；
 * 这类服务通常要求使用「应用密码 / 授权码」而非登录密码）。
 *
 * 说明：本客户端不做任何远端删除动作，只新增/覆盖目标文件，
 * 因此不存在误删远端既有数据（如相册备份）的风险。
 */
@Singleton
class WebDavClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 连接测试：对服务器地址发 PROPFIND（Depth: 0）。
     * 成功码 207（标准 WebDAV Multi-Status）/ 200；
     * 405 说明服务器在但未开放 PROPFIND，仍视为「可达且鉴权通过」。
     */
    fun testConnection(config: WebDavConfig): WebDavResult {
        val base = normalizeBase(config.url)
            ?: return WebDavResult.Failure("服务器地址无效：请以 http:// 或 https:// 开头")

        return runCatching {
            val request = newRequest(base, config)
                .method("PROPFIND", null)
                .header("Depth", "0")
                .build()
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    207, 200 -> WebDavResult.Success("连接成功（HTTP ${resp.code}）")
                    401, 403 -> WebDavResult.Failure(
                        "认证失败（HTTP ${resp.code}）：请检查账号密码；Nextcloud/坚果云需使用「应用密码」"
                    )
                    405 -> WebDavResult.Success("服务器可达（HTTP 405，未开放 PROPFIND）")
                    404 -> WebDavResult.Failure("路径不存在（HTTP 404）：请检查地址是否带正确的 WebDAV 根路径")
                    else -> WebDavResult.Failure("服务器返回 HTTP ${resp.code}")
                }
            }
        }.getOrElse { e ->
            WebDavResult.Failure("连接异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 上传一个文件到远端目录（目录不存在时自动逐级创建）。
     *
     * @param remoteDir 远端目录，如 /GenshinGachaHelper
     * @param fileName  文件名（无需编码，内部会做 URL 编码）
     */
    fun upload(
        config: WebDavConfig,
        remoteDir: String,
        fileName: String,
        bytes: ByteArray
    ): WebDavResult {
        val base = normalizeBase(config.url)
            ?: return WebDavResult.Failure("服务器地址无效：请以 http:// 或 https:// 开头")

        val dirSegments = parseSegments(remoteDir)

        // 目录不存在则创建（已存在返回 405，忽略）
        ensureDirectory(base, dirSegments, config)?.let { return it }

        val dirUrl = buildDirUrl(base, dirSegments)
        val fileUrl = "$dirUrl/${encodeSegment(fileName)}"

        return runCatching {
            val request = newRequest(fileUrl, config)
                .put(bytes.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200, 201, 204 -> WebDavResult.Success("已上传 $fileName")
                    401, 403 -> WebDavResult.Failure(
                        "认证失败（HTTP ${resp.code}）：请检查账号密码；Nextcloud/坚果云需使用「应用密码」"
                    )
                    404, 409 -> WebDavResult.Failure("远端目录不存在（HTTP ${resp.code}）：无法创建目录")
                    507 -> WebDavResult.Failure("远端空间不足（HTTP 507）")
                    else -> WebDavResult.Failure("上传失败：HTTP ${resp.code}")
                }
            }
        }.getOrElse { e ->
            WebDavResult.Failure("上传异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 逐级确保远端目录存在。全部成功（或已存在）返回 null，否则返回失败结果。
     */
    private fun ensureDirectory(
        base: String,
        segments: List<String>,
        config: WebDavConfig
    ): WebDavResult? {
        if (segments.isEmpty()) return null
        var current = base.trimEnd('/')
        for (segment in segments) {
            current = "$current/${encodeSegment(segment)}"
            val created = runCatching {
                val request = newRequest(current, config).method("MKCOL", null).build()
                client.newCall(request).execute().use { resp -> resp.code }
            }.getOrElse { e ->
                return WebDavResult.Failure("创建远端目录异常：${e.message ?: e.javaClass.simpleName}")
            }
            when (created) {
                200, 201, 204, 301, 302, 405 -> Unit // 创建成功 / 已存在
                401, 403 -> return WebDavResult.Failure("认证失败（HTTP $created）：无法创建远端目录")
                409 -> return WebDavResult.Failure("远端父目录不存在（HTTP 409）")
                else -> if (created >= 400) {
                    return WebDavResult.Failure("创建远端目录失败：HTTP $created")
                }
            }
        }
        return null
    }

    private fun newRequest(url: String, config: WebDavConfig): Request.Builder {
        val builder = Request.Builder().url(url)
        if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
            builder.header(
                "Authorization",
                Credentials.basic(config.username, config.password, Charsets.UTF_8)
            )
        }
        return builder
    }

    /**
     * 规范化服务器地址：缺协议自动补 https://，末尾统一补 /。
     */
    private fun normalizeBase(rawUrl: String): String? {
        var url = rawUrl.trim()
        if (url.isEmpty()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return url.trimEnd('/') + "/"
    }

    private fun parseSegments(dir: String): List<String> =
        dir.trim().split('/').map { it.trim() }.filter { it.isNotEmpty() }

    private fun buildDirUrl(base: String, segments: List<String>): String {
        val trimmed = base.trimEnd('/')
        return if (segments.isEmpty()) trimmed
        else "$trimmed/${segments.joinToString("/") { encodeSegment(it) }}"
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
}

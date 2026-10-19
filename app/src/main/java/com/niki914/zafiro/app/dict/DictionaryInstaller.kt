package com.niki914.zafiro.app.dict

import android.content.Context
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * 粤典（words.hk 全量 + 用户笔记）随应用一起发布。
 *
 * 数据库 31MB，压缩后 13MB 放在 assets 里，首次启动解压到 filesDir。
 * 为什么打包而不是联网下载：查词应该是离线的、即时的 —— 网络断了词典照样能用。
 */
object DictionaryInstaller {

    private const val ASSET = "dictionary.db.gz"
    private const val NAME = "dictionary.db"

    fun ensure(ctx: Context): Boolean {
        val target = File(ctx.filesDir, NAME)
        if (target.isFile && target.length() > 1_000_000) return true
        return runCatching {
            ctx.assets.open(ASSET).use { input ->
                GZIPInputStream(input).use { gz ->
                    target.outputStream().use { out -> gz.copyTo(out) }
                }
            }
            true
        }.getOrDefault(false)
    }

    fun isInstalled(ctx: Context): Boolean = File(ctx.filesDir, NAME).isFile
}

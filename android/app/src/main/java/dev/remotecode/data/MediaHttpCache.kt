package dev.remotecode.data

import java.io.File
import okhttp3.Cache
import okhttp3.OkHttpClient

/** サムネイルと拡大表示で原本を共有する。 */
internal fun mediaHttpClient(client: OkHttpClient, directory: File): OkHttpClient = client.newBuilder()
    .cache(Cache(directory, 64L * 1024 * 1024))
    .addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        val vary = response.headers.values("Vary").flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotBlank() }
        val headers = response.newBuilder()
            // 異なる認証情報間で画像を共有しない。
            .header("Vary", (vary + listOf("Authorization", "CF-Access-Client-Id", "CF-Access-Client-Secret")).distinctBy { it.lowercase() }.joinToString(", "))
        // ID はパスなので、内容の変更を再検証する。
        if (response.isSuccessful && !response.cacheControl.noStore && response.cacheControl.maxAgeSeconds > 60) {
            headers.header("Cache-Control", "private, max-age=60")
        }
        headers.build()
    }
    .build()

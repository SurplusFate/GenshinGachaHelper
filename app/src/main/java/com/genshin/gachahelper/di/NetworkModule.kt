package com.genshin.gachahelper.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 网络模块
 *
 * 全应用共享单个 OkHttpClient，复用连接池与线程池，避免每个 Service 各建一个
 * client 造成额外内存与连接开销。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // 请求会携带 Cookie / authkey 等凭据。OkHttp 自动跟随重定向时会把原始请求头
            // （含 Cookie）原样转发到目标主机，官方接口一旦返回跨域 3xx，凭据即可能泄露给第三方。
            // 关闭后重定向以 3xx 状态码返回，由调用方按失败处理。
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

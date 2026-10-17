package com.comfyui.mobile

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.comfyui.mobile.data.AppModule

/** One loader/cache owner per process, including after an Activity is recreated. */
class ComfyApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .callFactory { AppModule.provideRepository().hostAwareCallFactory }
        .components {
            add(ImageDecoderDecoder.Factory())
            add(VideoFrameDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}

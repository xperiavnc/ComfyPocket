package com.comfyui.mobile.data

object AppModule {
    fun provideRepository(): ComfyRepository = ComfyRepository.getInstance()
}


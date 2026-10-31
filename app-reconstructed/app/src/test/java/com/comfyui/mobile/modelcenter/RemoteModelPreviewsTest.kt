package com.comfyui.mobile.modelcenter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteModelPreviewsTest {
    private fun model(vararg urls: String) =
        LocalModel(id = "m", provider = ModelProvider(images = urls.map { ModelPreview(it) }))

    @Test fun videosAndUnsafeLinksAreSkippedSoTheCoverFallsToTheNextStill() {
        val item = model(
            "https://image.civitai.com/a/clip.mp4",
            "http://image.civitai.com/a/plain-http.png",
            "https://user:pw@image.civitai.com/a/secret.png",
            "https://image.civitai.com/a/first.jpeg",
            "https://image.civitai.com/a/clip.webm?token=1",
            "https://image.civitai.com/a/second.png",
            "https://image.civitai.com/a/first.jpeg"
        )
        assertEquals(listOf("https://image.civitai.com/a/first.jpeg", "https://image.civitai.com/a/second.png"),
            remoteModelPreviews(item))
        assertEquals("https://image.civitai.com/a/first.jpeg", remoteModelPreview(item))
        assertEquals("https://image.civitai.com/a/second.png", remoteModelPreview(item, 1))
    }

    @Test fun noProviderImagesMeansNoCandidates() {
        assertEquals(emptyList<String>(), remoteModelPreviews(LocalModel(id = "none")))
        assertNull(remoteModelPreview(model("https://image.civitai.com/a/only.mp4")))
    }
}

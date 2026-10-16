package com.comfyui.mobile.ui.screens.modelbrowser

/**
 * Convert HTML content into structured plain text with clean paragraph breaks (\n\n)
 * and line breaks (\n), while ensuring all HTML tags (like <p>, </p>, <br>) are stripped
 * and never shown on screen.
 */
fun cleanHtmlToPlainText(raw: String?): String {
    if (raw.isNullOrBlank()) return ""

    // 1. Decode escaped brackets &lt; and &gt; so escaped HTML tags are recognized as real tags
    var text = raw
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)

    // 2. Map paragraph and line break tags to newlines:
    // </p>, </div>, </h1-h6> -> \n\n (paragraph break)
    // <br>, <br/>, <br /> -> \n (line break)
    // <li> -> \n• (bullet point)
    text = text
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p\\s*>"), "\n\n")
        .replace(Regex("(?i)<p[^>]*>"), "")
        .replace(Regex("(?i)</h[1-6]\\s*>"), "\n\n")
        .replace(Regex("(?i)<h[1-6][^>]*>"), "\n")
        .replace(Regex("(?i)</div\\s*>"), "\n")
        .replace(Regex("(?i)<div[^>]*>"), "")
        .replace(Regex("(?i)<li[^>]*>"), "\n• ")
        .replace(Regex("(?i)</li\\s*>"), "")

    // 3. Strip all remaining HTML tags
    text = text.replace(Regex("<[^>]+>"), "")

    // 4. Use android.text.Html to unescape all other HTML entities (&nbsp;, &quot;, &#39;, etc.)
    val parsed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
        android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    } else {
        @Suppress("DEPRECATION")
        android.text.Html.fromHtml(text).toString()
    }

    // 5. Normalize carriage returns and excessive empty lines
    return parsed
        .replace(Regex("\r\n|\r"), "\n")
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

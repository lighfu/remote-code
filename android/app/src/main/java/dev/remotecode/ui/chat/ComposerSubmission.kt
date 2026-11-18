package dev.remotecode.ui.chat

import dev.remotecode.data.Entry
import java.util.UUID

/** 送信後の編集を残し、受理された本文と添付だけを取り除く。 */
internal class ComposerSubmission<I, F>(
    private val originalText: String,
    private val textRevision: Long,
    val images: List<I>,
    val files: List<F>,
) {
    val clientMessageId = UUID.randomUUID().toString()
    var acknowledged = false
        private set

    fun matchesReceipt(row: Entry): Boolean =
        row.kind == "user" && row.clientMessageId == clientMessageId

    fun hasReceipt(rows: List<Entry>): Boolean = rows.any(::matchesReceipt)

    fun acknowledge(
        currentText: String,
        currentTextRevision: Long,
        currentImages: MutableList<I>,
        currentFiles: MutableList<F>,
        clearText: () -> Unit,
    ) {
        if (acknowledged) return
        acknowledged = true
        if (currentTextRevision == textRevision && currentText == originalText) clearText()
        currentImages.removeAll { current -> images.any { it === current } }
        currentFiles.removeAll { current -> files.any { it === current } }
    }
}

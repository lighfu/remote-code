package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.data.GitFile

internal enum class GitFileSort(val label: Int) {
    Name(R.string.git_file_sort_name), Modified(R.string.git_file_sort_modified), Size(R.string.git_file_sort_size),
    Lines(R.string.git_file_sort_lines), Status(R.string.git_file_sort_status);

    fun sorted(files: List<GitFile>, ascending: Boolean): List<GitFile> {
        if (this == Name) return files.sortedWith(if (ascending) fileNameOrder else fileNameOrder.reversed())

        val byValue = if (ascending) compareBy<GitFile> { value(it) } else compareByDescending { value(it) }
        // Unknown values follow measured values in either direction. Break ties by name.
        return files.sortedWith(compareBy<GitFile> { value(it) == null }.then(byValue).then(fileNameOrder))
    }

    private fun value(file: GitFile): Long? = when (this) {
        Name -> null
        Modified -> file.modifiedAt
        Size -> file.sizeBytes
        Lines -> if (file.binary || file.lineStatsPartial) null else file.additions.toLong() + file.deletions
        Status -> when {
            file.conflict -> 0L
            file.staged && file.unstaged -> 1L
            file.staged -> 2L
            file.untracked -> 4L
            file.unstaged -> 3L
            else -> null
        }
    }
}

private val fileNameOrder = compareBy<GitFile, String>(String.CASE_INSENSITIVE_ORDER) {
    it.path.substringAfterLast('/').substringAfterLast('\\')
}.thenBy(String.CASE_INSENSITIVE_ORDER) { it.path }.thenBy { it.path }

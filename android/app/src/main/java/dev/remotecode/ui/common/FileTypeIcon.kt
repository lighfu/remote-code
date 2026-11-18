package dev.remotecode.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.remotecode.data.ProjectFileKind
import dev.remotecode.data.projectFileKind

@Composable
fun FileTypeIcon(name: String, modifier: Modifier = Modifier, directory: Boolean = false) {
    val kind = remember(name, directory) { projectFileKind(name, directory) }
    val icon = when (kind) {
        ProjectFileKind.FOLDER -> Icons.Rounded.Folder
        ProjectFileKind.CODE -> Icons.Rounded.Code
        ProjectFileKind.CONFIG -> Icons.Rounded.DataObject
        ProjectFileKind.SCRIPT -> Icons.Rounded.Terminal
        ProjectFileKind.DOCUMENT -> Icons.Rounded.Article
        ProjectFileKind.IMAGE -> Icons.Rounded.Image
        ProjectFileKind.VIDEO -> Icons.Rounded.Movie
        ProjectFileKind.AUDIO -> Icons.Rounded.AudioFile
        ProjectFileKind.PDF -> Icons.Rounded.PictureAsPdf
        ProjectFileKind.ARCHIVE -> Icons.Rounded.FolderZip
        ProjectFileKind.TABLE -> Icons.Rounded.TableChart
        ProjectFileKind.DATABASE -> Icons.Rounded.Storage
        ProjectFileKind.FONT -> Icons.Rounded.FontDownload
        ProjectFileKind.BINARY -> Icons.Rounded.Memory
        ProjectFileKind.FILE -> Icons.Rounded.Description
    }
    val colors = MaterialTheme.colorScheme
    val tint = when (kind) {
        ProjectFileKind.FOLDER, ProjectFileKind.CODE, ProjectFileKind.TABLE -> colors.primary
        ProjectFileKind.CONFIG, ProjectFileKind.AUDIO, ProjectFileKind.ARCHIVE, ProjectFileKind.DATABASE -> colors.secondary
        ProjectFileKind.SCRIPT, ProjectFileKind.IMAGE, ProjectFileKind.VIDEO -> colors.tertiary
        ProjectFileKind.PDF -> colors.error
        else -> colors.onSurfaceVariant
    }
    Icon(icon, kind.label, modifier, tint = tint)
}

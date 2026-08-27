package com.willykez.repomaster.ui.screens.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.willykez.repomaster.git.FileNode
import com.willykez.repomaster.ui.theme.Amber
import com.willykez.repomaster.ui.theme.CommandBlue
import com.willykez.repomaster.ui.theme.Emerald
import com.willykez.repomaster.ui.theme.StatusClean

/**
 * A distinct icon (and tint) per file type instead of one generic file icon for everything —
 * the same "recognize a file by its icon before you even read the name" convention every
 * proper code editor's file tree uses. Coarser than a full per-extension icon font (this app
 * doesn't ship one), but covers the file types that actually show up in a typical repo.
 */
@Composable
fun fileIconFor(node: FileNode): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    if (node.isDirectory) return Icons.Filled.Folder to CommandBlue

    val name = node.name.substringAfterLast('/').lowercase()
    val ext = name.substringAfterLast('.', missingDelimiterValue = "")

    return when {
        name == "readme.md" || name == "readme" -> Icons.Filled.Info to Amber
        name == ".gitignore" || name == ".gitattributes" -> Icons.Filled.VisibilityOff to StatusClean
        name == "dockerfile" -> Icons.Filled.Build to CommandBlue
        ext in setOf("kt", "kts", "java") -> Icons.Filled.Code to CommandBlue
        ext in setOf("js", "jsx", "ts", "tsx") -> Icons.Filled.Javascript to Amber
        ext == "py" -> Icons.Filled.Terminal to Emerald
        ext in setOf("json") -> Icons.Filled.DataObject to Amber
        ext in setOf("yml", "yaml", "toml") -> Icons.Filled.Settings to StatusClean
        ext in setOf("xml", "html", "htm") -> Icons.Filled.Code to CommandBlue
        ext in setOf("md", "markdown") -> Icons.Filled.Description to StatusClean
        ext in setOf("sql", "db", "sqlite") -> Icons.Filled.Storage to Emerald
        ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp") -> Icons.Filled.Image to Amber
        ext in setOf("gradle", "properties", "env") -> Icons.Filled.Build to StatusClean
        ext in setOf("zip", "tar", "gz", "jar", "apk", "aar") -> Icons.Filled.FolderZip to StatusClean
        ext in setOf("sh", "bash", "zsh") -> Icons.Filled.Terminal to StatusClean
        ext == "txt" -> Icons.Filled.Description to StatusClean
        else -> Icons.Filled.InsertDriveFile to StatusClean
    }
}

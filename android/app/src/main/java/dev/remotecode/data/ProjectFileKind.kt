package dev.remotecode.data

import java.util.Locale
import androidx.annotation.StringRes
import dev.remotecode.R
import dev.remotecode.i18n.appString

enum class ProjectFileKind(@StringRes private val labelRes: Int) {
    FOLDER(R.string.core_file_kind_folder), CODE(R.string.core_file_kind_code), CONFIG(R.string.core_file_kind_config), SCRIPT(R.string.core_file_kind_script),
    DOCUMENT(R.string.core_file_kind_document), IMAGE(R.string.core_file_kind_image), VIDEO(R.string.core_file_kind_video), AUDIO(R.string.core_file_kind_audio), PDF(R.string.core_file_kind_pdf),
    ARCHIVE(R.string.core_file_kind_archive), TABLE(R.string.core_file_kind_table), DATABASE(R.string.core_file_kind_database),
    FONT(R.string.core_file_kind_font), BINARY(R.string.core_file_kind_binary), FILE(R.string.core_file_kind_file);

    val label: String get() = appString(labelRes)
}

private val configNames = setOf(
    ".gitignore", ".gitattributes", ".gitmodules", ".gitkeep", ".editorconfig", ".npmrc", ".nvmrc",
    ".yarnrc", ".yarnrc.yml", ".eslintrc", ".prettierrc", ".babelrc", ".dockerignore", ".env",
)
private val scriptNames = setOf("dockerfile", "containerfile", "makefile", "gnumakefile", "rakefile", "gemfile", "gradlew", "configure", "justfile")
private val documentNames = setOf("readme", "license", "licence", "notice", "copying", "authors", "changelog")

fun projectFileKind(name: String, directory: Boolean = false): ProjectFileKind {
    if (directory) return ProjectFileKind.FOLDER
    val file = name.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)
    if (file in configNames || file.startsWith(".env.")) return ProjectFileKind.CONFIG
    if (file in scriptNames || file.startsWith("dockerfile.")) return ProjectFileKind.SCRIPT
    if (file in documentNames) return ProjectFileKind.DOCUMENT
    val extension = file.substringAfterLast('.', "")
    return when (extension) {
        "ts", "tsx", "js", "jsx", "mjs", "cjs", "kt", "kts", "java", "cs", "c", "h", "cc", "cpp", "cxx", "hpp", "rs", "go", "py", "pyw", "rb", "php", "swift", "m", "mm", "dart", "lua", "r", "scala", "clj", "cljs", "ex", "exs", "erl", "hrl", "hs", "fs", "fsx", "vb", "html", "htm", "css", "scss", "sass", "less", "vue", "svelte", "astro", "sql", "graphql", "gql", "proto", "shader", "hlsl", "glsl", "cg", "wgsl" -> ProjectFileKind.CODE
        "json", "jsonc", "json5", "yaml", "yml", "toml", "ini", "conf", "config", "cfg", "properties", "xml", "plist", "lock", "gradle", "props", "targets", "csproj", "sln", "vcxproj", "iml" -> ProjectFileKind.CONFIG
        "sh", "bash", "zsh", "fish", "ps1", "psm1", "psd1", "cmd", "bat", "command" -> ProjectFileKind.SCRIPT
        "md", "mdx", "markdown", "txt", "rst", "rtf", "adoc", "doc", "docx", "odt", "tex", "log" -> ProjectFileKind.DOCUMENT
        "png", "jpg", "jpeg", "gif", "webp", "avif", "heic", "heif", "bmp", "ico", "tif", "tiff", "svg", "psd", "ai", "eps", "exr", "tga" -> ProjectFileKind.IMAGE
        "mp4", "mov", "m4v", "webm", "mkv", "avi", "wmv", "flv", "mpeg", "mpg", "3gp" -> ProjectFileKind.VIDEO
        "mp3", "wav", "flac", "ogg", "opus", "aac", "m4a", "aiff", "wma", "mid", "midi" -> ProjectFileKind.AUDIO
        "pdf" -> ProjectFileKind.PDF
        "zip", "7z", "rar", "tar", "gz", "gzip", "bz2", "xz", "zst", "tgz", "br" -> ProjectFileKind.ARCHIVE
        "csv", "tsv", "xls", "xlsx", "ods", "numbers" -> ProjectFileKind.TABLE
        "db", "sqlite", "sqlite3", "mdb", "accdb" -> ProjectFileKind.DATABASE
        "ttf", "otf", "woff", "woff2", "eot" -> ProjectFileKind.FONT
        "exe", "dll", "so", "dylib", "a", "o", "obj", "lib", "class", "jar", "wasm", "bin", "apk", "aab", "dex", "msi", "dmg", "iso", "pdb" -> ProjectFileKind.BINARY
        else -> ProjectFileKind.FILE
    }
}

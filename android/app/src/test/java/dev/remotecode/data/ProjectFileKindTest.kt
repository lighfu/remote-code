package dev.remotecode.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectFileKindTest {
    @Test fun folderClassificationWinsOverExtensions() {
        assertEquals(ProjectFileKind.FOLDER, projectFileKind("source.ts", directory = true))
    }

    @Test fun dotfilesAndExtensionlessProjectFilesAreRecognized() {
        listOf(".gitignore", ".gitattributes", ".editorconfig", ".env", ".env.production", "package.json", "package-lock.json").forEach {
            assertEquals(it, ProjectFileKind.CONFIG, projectFileKind(it))
        }
        assertEquals(ProjectFileKind.DOCUMENT, projectFileKind("README"))
        assertEquals(ProjectFileKind.SCRIPT, projectFileKind("Dockerfile"))
        assertEquals(ProjectFileKind.SCRIPT, projectFileKind("gradlew"))
    }

    @Test fun classificationIgnoresCaseAndParentFolderNames() {
        assertEquals(ProjectFileKind.CODE, projectFileKind("folder.jpg/COMPONENT.TSX"))
        assertEquals(ProjectFileKind.CODE, projectFileKind("C:\\code\\Main.KT"))
        assertEquals(ProjectFileKind.SCRIPT, projectFileKind("START.CMD"))
        assertEquals(ProjectFileKind.IMAGE, projectFileKind("PHOTO.JPEG"))
        assertEquals(ProjectFileKind.VIDEO, projectFileKind("recording.MP4"))
    }

    @Test fun commonMediaDocumentsAndArtifactsHaveDistinctKinds() {
        val samples = mapOf(
            "readme.md" to ProjectFileKind.DOCUMENT, "options.yaml" to ProjectFileKind.CONFIG,
            "sound.flac" to ProjectFileKind.AUDIO, "report.pdf" to ProjectFileKind.PDF,
            "archive.tar.gz" to ProjectFileKind.ARCHIVE, "usage.csv" to ProjectFileKind.TABLE,
            "state.sqlite3" to ProjectFileKind.DATABASE, "font.woff2" to ProjectFileKind.FONT,
            "runtime.wasm" to ProjectFileKind.BINARY, "app.apk" to ProjectFileKind.BINARY,
            "unknown.custom" to ProjectFileKind.FILE, "no-extension" to ProjectFileKind.FILE,
        )
        samples.forEach { (name, kind) -> assertEquals(name, kind, projectFileKind(name)) }
    }
}

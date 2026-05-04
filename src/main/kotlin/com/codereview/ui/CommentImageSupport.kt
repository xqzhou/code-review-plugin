package com.codereview.ui

import com.codereview.git.GitBranchProvider
import com.intellij.openapi.project.Project
import java.awt.Canvas
import java.awt.Image
import java.awt.MediaTracker
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

object CommentImageSupport {
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp")
    private val remoteCacheBasenameInvalid = Regex("[^A-Za-z0-9._-]+")

    fun isSupportedImage(file: File): Boolean =
        file.isFile && file.extension.lowercase() in imageExtensions

    fun saveClipboardImage(project: Project, image: Image): String {
        return saveBufferedImage(project, toBufferedImage(image))
    }

    fun saveBufferedImage(project: Project, image: BufferedImage): String {
        val target = nextAttachmentPath(project, "png")
        Files.createDirectories(target.parent)
        ImageIO.write(image, "png", target.toFile())
        return projectRelativePath(project, target)
    }

    fun copyImageFile(project: Project, source: File): String {
        require(isSupportedImage(source)) { "Unsupported image file: ${source.name}" }
        val extension = source.extension.lowercase().ifBlank { "png" }
        val target = nextAttachmentPath(project, extension)
        Files.createDirectories(target.parent)
        Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
        return projectRelativePath(project, target)
    }

    fun resolve(project: Project, rawPath: String): File? {
        val path = rawPath.trim().trim('"', '\'')
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return resolveRemoteCache(project, path)
        }
        val fromUri = runCatching {
            if (path.startsWith("file:")) Path.of(URI(path)).toFile() else null
        }.getOrNull()
        if (fromUri != null) return fromUri

        val basePath = project.basePath ?: return null
        val projectRelative = Path.of(basePath).resolve(path).normalize().toFile()
        if (projectRelative.exists()) return projectRelative

        return Path.of(basePath, ".code-review").resolve(path).normalize().toFile().takeIf { it.exists() }
    }

    /**
     * Resolve a remote upload URL to its deterministic local cache file.
     *
     * Cache layout matches `sync.py::_target_local_path_for`:
     *   `.code-review/assets/<branch>/remote-<sha1(url)[:12]>-<sanitized-basename>`
     *
     * Returns the file if it has been downloaded by `sync-comments`; null otherwise.
     * The renderer falls back to a `[image](url)` placeholder when null.
     */
    private fun resolveRemoteCache(project: Project, url: String): File? {
        val basePath = project.basePath ?: return null
        val branch = GitBranchProvider.safeBranchFileName(GitBranchProvider.currentBranch(project))
        val digest = sha1Hex(url).substring(0, 12)
        val basename = remoteUrlBasename(url)
        val sanitized = remoteCacheBasenameInvalid.replace(basename, "_").ifBlank { "image" }
        val target = Path.of(basePath, ".code-review", "assets", branch, "remote-$digest-$sanitized")
            .normalize().toFile()
        return target.takeIf { it.isFile }
    }

    private fun sha1Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }

    private fun remoteUrlBasename(url: String): String {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val tail = withoutQuery.substringAfterLast('/', missingDelimiterValue = withoutQuery)
        return tail.ifBlank { "image" }
    }

    private fun nextAttachmentPath(project: Project, extension: String): Path {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val branch = GitBranchProvider.safeBranchFileName(GitBranchProvider.currentBranch(project))
        val name = "comment-image-${Instant.now().toEpochMilli()}-${UUID.randomUUID().toString().take(8)}.$extension"
        return Path.of(basePath, ".code-review", "assets", branch, name)
    }

    private fun projectRelativePath(project: Project, target: Path): String {
        val basePath = project.basePath ?: return target.toString()
        return Path.of(basePath).relativize(target).toString().replace(File.separatorChar, '/')
    }

    private fun toBufferedImage(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        waitForImage(image)
        val width = image.getWidth(null).coerceAtLeast(1)
        val height = image.getHeight(null).coerceAtLeast(1)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { buffered ->
            val graphics = buffered.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.drawImage(image, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }
    }

    private fun waitForImage(image: Image) {
        if (image.getWidth(null) > 0 && image.getHeight(null) > 0) return
        val tracker = MediaTracker(Canvas())
        tracker.addImage(image, 0)
        runCatching { tracker.waitForID(0, 5000) }
    }
}

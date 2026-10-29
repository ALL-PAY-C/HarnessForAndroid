package dev.dsh.mobile.core.protocol.web

import java.io.File
import java.io.IOException

/**
 * 用户工作区沙箱：一切落盘路径必须是工作区内的相对路径。
 * 拒绝 ..、绝对路径、盘符、以及经符号链接逃出工作区的路径（计划 T8「路径必须限制在工作区内」）。
 */
class WorkspaceSandbox(root: File) {

    val root: File = try {
        root.absoluteFile.canonicalFile
    } catch (e: IOException) {
        throw WebToolException(WebErrorCode.OutsideWorkspace, "工作区路径无法解析：" + root.path)
    }

    fun resolve(relative: String): File {
        val raw = relative.trim()
        if (raw.contains('\u0000')) throw WebToolException(WebErrorCode.BadRequest, "路径包含非法字符")
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.startsWith("~") || DRIVE.matches(normalized)) {
            throw WebToolException(WebErrorCode.OutsideWorkspace, "只接受工作区内的相对路径：" + relative)
        }
        val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) {
            throw WebToolException(WebErrorCode.OutsideWorkspace, "路径不允许包含 ..：" + relative)
        }
        val candidate = if (parts.isEmpty()) root else File(root, parts.joinToString(File.separator))
        val canonical = try {
            candidate.canonicalFile
        } catch (e: IOException) {
            throw WebToolException(WebErrorCode.OutsideWorkspace, "路径无法解析：" + relative)
        }
        val rootPath = root.canonicalPath
        val inside = canonical.path == rootPath || canonical.path.startsWith(rootPath + File.separator)
        if (!inside) throw WebToolException(WebErrorCode.OutsideWorkspace, "路径逃出工作区：" + relative)
        return canonical
    }

    /** 校验一个已经拼好的绝对路径仍落在工作区内（下载最终落盘前的最后一道检查）。 */
    fun ensureInside(file: File): File {
        val canonical = file.canonicalFile
        val rootPath = root.canonicalPath
        if (canonical.path != rootPath && !canonical.path.startsWith(rootPath + File.separator)) {
            throw WebToolException(WebErrorCode.OutsideWorkspace, "路径逃出工作区：" + file.path)
        }
        return canonical
    }

    private companion object {
        val DRIVE = Regex("^[A-Za-z]:.*")
    }
}

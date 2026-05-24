package com.pocketagent.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SkillManager {
    private const val TAG = "SkillManager"
    private var skillsRoot: File? = null
    private var appContext: Context? = null

    enum class Category(val dirName: String, val displayName: String, val isSystem: Boolean) {
        MAIN_SKILLS("main-skills", "主 Agent 技能", true),
        EXECUTOR_SKILLS("executor-skills", "子 Agent 技能", true),
        AUTO_SKILLS("auto-skills", "自动技能", false);

        companion object {
            fun fromDirName(name: String): Category? =
                entries.find { it.dirName == name }
        }
    }

    data class Skill(
        val name: String,
        val description: String,
        val category: Category,
        val path: String,
        val content: String = ""
    )

    // ─── 初始化 ─────────────────────────────────────

    fun init(skillsDir: String) {
        skillsRoot = File(skillsDir)
        skillsRoot?.mkdirs()
        Category.entries.forEach { cat ->
            File(skillsRoot, cat.dirName).mkdirs()
        }
        Log.i(TAG, "SkillManager initialized: $skillsDir")
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        val externalPath = "/storage/emulated/0/手机agent开发/Pocket-Agent/agent/skills"
        val externalDir = File(externalPath)
        if (externalDir.exists()) {
            init(externalDir.absolutePath)
            return
        }
        try {
            val runtimeDir = com.pocketagent.app.update.CodeSyncManager.getInstance().getRuntimeDir()
            init(File(runtimeDir, "agent/skills").absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "CodeSyncManager failed, using internal storage", e)
            val internalPath = File(context.filesDir, "agent/skills").absolutePath
            init(internalPath)
        }
    }

    fun rescan() {
        val ctx = appContext ?: return
        // Re-evaluate which skills root to use (same logic as init(context))
        val externalPath = "/storage/emulated/0/手机agent开发/Pocket-Agent/agent/skills"
        val externalDir = File(externalPath)
        if (externalDir.exists()) {
            init(externalDir.absolutePath)
            Log.i(TAG, "Rescanned skills root (external): $externalPath")
            return
        }
        try {
            val runtimeDir = com.pocketagent.app.update.CodeSyncManager.getInstance().getRuntimeDir()
            val skillsDir = File(runtimeDir, "agent/skills").absolutePath
            init(skillsDir)
            Log.i(TAG, "Rescanned skills root (runtime): $skillsDir")
        } catch (e: Exception) {
            Log.w(TAG, "Rescan failed, keeping current skills root", e)
        }
    }

    private fun getSkillsRoot(): File =
        skillsRoot ?: throw IllegalStateException("SkillManager not initialized")

    // ─── 路径安全校验 ───────────────────────────────

    /** 校验 path 是否限制在 skillsRoot 内，防止路径穿越 */
    private fun isPathSafe(path: String): Boolean {
        val root = getSkillsRoot().canonicalPath
        val target = File(getSkillsRoot(), path).canonicalPath
        return target.startsWith("$root/") || target == root
    }

    // ─── 读取（IO 线程）─────────────────────────────

    suspend fun getSkills(category: Category): List<Skill> = withContext(Dispatchers.IO) {
        val dir = File(getSkillsRoot(), category.dirName)
        if (!dir.exists()) return@withContext emptyList()

        when (category) {
            Category.AUTO_SKILLS -> {
                dir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.flatMap { subCatDir ->
                        subCatDir.listFiles()
                            ?.filter { it.isDirectory }
                            ?.mapNotNull { skillDir -> readSingleSkill(skillDir, category) }
                            ?: emptyList()
                    }
                    ?: emptyList()
            }
            else -> collectAllSkills(dir, category)
        }
    }

    private fun collectAllSkills(dir: File, category: Category): List<Skill> {
        val result = mutableListOf<Skill>()
        val skillFile = findSkillFile(dir)
        if (skillFile != null) {
            try {
                val content = skillFile.readText()
                val (name, description) = parseFrontmatter(content)
                val relativePath = dir.relativeTo(getSkillsRoot()).path
                result.add(Skill(
                    name = name ?: dir.name,
                    description = description ?: "",
                    category = category,
                    path = relativePath,
                    content = content
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read skill file: ${skillFile.absolutePath}", e)
            }
            return result
        }
        for (child in dir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            try {
                result.addAll(collectAllSkills(child, category))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to collect skills from: ${child.absolutePath}", e)
            }
        }
        return result
    }

    private fun readSingleSkill(dir: File, category: Category): Skill? {
        val skillFile = findSkillFile(dir) ?: return null
        return try {
            val content = skillFile.readText()
            val (name, description) = parseFrontmatter(content)
            val relativePath = dir.relativeTo(getSkillsRoot()).path
            Skill(
                name = name ?: dir.name,
                description = description ?: "",
                category = category,
                path = relativePath,
                content = content
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read skill: ${skillFile.absolutePath}", e)
            null
        }
    }

    suspend fun getAllSkills(): List<Skill> = withContext(Dispatchers.IO) {
        Category.entries.flatMap { getSkills(it) }
    }

    suspend fun getSkillByPath(path: String): Skill? = withContext(Dispatchers.IO) {
        try {
            if (!isPathSafe(path)) return@withContext null
            val file = File(getSkillsRoot(), path)
            if (!file.isDirectory) return@withContext null
            val skillFile = findSkillFile(file) ?: return@withContext null
            val content = skillFile.readText()
            val (name, description) = parseFrontmatter(content)
            val category = Category.fromDirName(path.substringBefore("/"))
                ?: return@withContext null
            Skill(
                name = name ?: file.name,
                description = description ?: "",
                category = category,
                path = path,
                content = content
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get skill by path: $path", e)
            null
        }
    }

    // ─── 创建（IO 线程）─────────────────────────────

    suspend fun createSkill(
        name: String,
        description: String,
        content: String,
        category: Category = Category.AUTO_SKILLS
    ): Skill? = withContext(Dispatchers.IO) {
        val skillDir = when (category) {
            Category.AUTO_SKILLS -> File(File(getSkillsRoot(), category.dirName), "executor/$name")
            else -> File(File(getSkillsRoot(), category.dirName), name)
        }
        if (skillDir.exists()) {
            Log.w(TAG, "Skill already exists: $name")
            return@withContext null
        }

        skillDir.mkdirs()
        val skillFile = File(skillDir, "SKILL.md")
        val frontmatter = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: $description")
            appendLine("---")
            appendLine()
            appendLine(content.trim())
        }
        try {
            skillFile.writeText(frontmatter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write skill: ${skillFile.absolutePath}", e)
            return@withContext null
        }

        val relativePath = skillDir.relativeTo(getSkillsRoot()).path
        Skill(name, description, category, relativePath, frontmatter)
    }

    // ─── 校验（纯逻辑，不需 IO 线程）────────────────

    fun isValidSkillName(name: String): Boolean {
        if (name.isBlank()) return false
        return name.matches(Regex("^[a-z0-9][a-z0-9-]*[a-z0-9]$")) ||
               name.matches(Regex("^[a-z0-9]$"))
    }

    data class SkillValidation(
        val valid: Boolean,
        val warnings: List<String>
    )

    fun validateSkillFormat(name: String, description: String, content: String): SkillValidation {
        val warnings = mutableListOf<String>()
        if (name.isBlank()) warnings += "技能名称不能为空"
        else if (!isValidSkillName(name)) warnings += "技能名称不规范：仅支持小写字母、数字和连字符，且不能以连字符开头或结尾"
        if (description.isBlank()) warnings += "技能描述不能为空"
        else if (description.length < 5) warnings += "技能描述过短，建议至少 5 个字"
        if (content.isBlank()) warnings += "SKILL.md 正文不能为空"
        else {
            val trimmed = content.trimStart()
            if (!trimmed.startsWith("---")) {
                warnings += "建议添加 YAML frontmatter 格式（--- 开头），便于解析元数据"
            } else {
                val endIndex = trimmed.indexOf("---", 3)
                if (endIndex < 0) warnings += "YAML frontmatter 未闭合，缺少结尾 ---"
                else {
                    val body = trimmed.substring(endIndex + 3).trim()
                    if (body.length < 10) warnings += "SKILL.md 正文内容过少，建议至少包含步骤描述"
                }
            }
        }
        return SkillValidation(warnings.isEmpty(), warnings)
    }

    // ─── 更新（IO 线程）─────────────────────────────

    suspend fun updateSkill(path: String, name: String, description: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isPathSafe(path)) return@withContext false
            val category = Category.fromDirName(path.substringBefore("/")) ?: return@withContext false
            if (category.isSystem) return@withContext false

            val skillDir = File(getSkillsRoot(), path)
            if (!skillDir.isDirectory) return@withContext false

            val skillFile = findSkillFile(skillDir) ?: File(skillDir, "SKILL.md")
            val frontmatter = buildString {
                appendLine("---")
                appendLine("name: $name")
                appendLine("description: $description")
                appendLine("---")
                appendLine()
                appendLine(content.trim())
            }
            try {
                skillFile.writeText(frontmatter)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update skill: ${skillFile.absolutePath}", e)
                return@withContext false
            }
            true
        }

    // ─── 导出（IO 线程）─────────────────────────────

    /**
     * 导出单个技能到目标目录
     * @param path 技能路径（如 "auto-skills/phone-control"）
     * @param destDir 目标目录路径（如 Downloads/PocketAgent_Skills）
     * @return 导出的目录路径，失败返回 null
     */
    suspend fun exportSkill(path: String, destDir: String): String? = withContext(Dispatchers.IO) {
        if (!isPathSafe(path)) return@withContext null
        val skillDir = File(getSkillsRoot(), path)
        if (!skillDir.isDirectory) return@withContext null
        val exportDir = File(destDir, skillDir.name)
        try {
            skillDir.copyRecursively(exportDir, overwrite = true)
            Log.i(TAG, "Exported: $path -> $exportDir")
            exportDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: $path", e)
            null
        }
    }

    suspend fun batchExportSkills(paths: List<String>, destDir: String): List<String> = withContext(Dispatchers.IO) {
        paths.mapNotNull { path -> exportSkill(path, destDir) }
    }

    // ─── 删除（IO 线程）─────────────────────────────

    suspend fun deleteSkill(path: String): Boolean = withContext(Dispatchers.IO) {
        if (!isPathSafe(path)) return@withContext false
        val category = Category.fromDirName(path.substringBefore("/")) ?: return@withContext false
        if (category.isSystem) return@withContext false

        val skillDir = File(getSkillsRoot(), path)
        if (!skillDir.isDirectory) return@withContext false

        skillDir.deleteRecursively()
    }

    // ─── 内部工具 ─────────────────────────────────

    private fun findSkillFile(dir: File): File? {
        for (name in listOf("SKILL.md", "skill.md", "Skill.md")) {
            val file = File(dir, name)
            if (file.exists() && file.isFile) return file
        }
        return null
    }

    /** 解析 YAML frontmatter，支持双引号包裹的值（值中的冒号不会被误拆） */
    private fun parseFrontmatter(content: String): Pair<String?, String?> {
        var name: String? = null
        var description: String? = null

        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return name to description

        val endIndex = trimmed.indexOf("---", 3)
        if (endIndex < 0) return name to description

        val frontmatter = trimmed.substring(3, endIndex).trim()
        for (line in frontmatter.lines()) {
            val colonIndex = line.indexOf(':')
            if (colonIndex < 0) continue
            val key = line.substring(0, colonIndex).trim()
            // 值中可能含冒号，取冒号后的全部内容并去除首尾空格和引号
            var value = line.substring(colonIndex + 1).trim()
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
            }
            when (key) {
                "name" -> name = value
                "description" -> description = value
            }
        }
        return name to description
    }

    fun getContentBody(content: String): String {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return trimmed
        val endIndex = trimmed.indexOf("---", 3)
        if (endIndex < 0) return trimmed
        return trimmed.substring(endIndex + 3).trim()
    }
}

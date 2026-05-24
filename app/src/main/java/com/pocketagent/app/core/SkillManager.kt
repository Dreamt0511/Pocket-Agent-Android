package com.pocketagent.app.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 技能管理器 — 管理 Agent 技能目录的增改查删
 *
 * 技能目录结构（对应主仓库 Pocket-Agent/agent/skills/）：
 *   main-skills/     — 系统技能（只读，不可删除）
 *   executor-skills/ — 执行器技能（只读，不可删除）
 *   auto-skills/     — 模型自动沉淀技能（可增改查删）
 *
 * 每个技能是一个目录，内含 SKILL.md 文件。
 * SKILL.md 格式：YAML frontmatter + Markdown 正文
 */
object SkillManager {
    private const val TAG = "SkillManager"
    private var skillsRoot: File? = null

    /** 技能分类 */
    enum class Category(val dirName: String, val displayName: String, val isSystem: Boolean) {
        MAIN_SKILLS("main-skills", "主技能", true),
        EXECUTOR_SKILLS("executor-skills", "执行技能", true),
        AUTO_SKILLS("auto-skills", "自动技能", false);

        companion object {
            fun fromDirName(name: String): Category? =
                entries.find { it.dirName == name }

            /** 将 auto-skills 下的子目录名映射回分类 */
            fun fromSubDir(dirName: String): Category? = when (dirName) {
                "executor" -> EXECUTOR_SKILLS
                "main" -> MAIN_SKILLS
                else -> null
            }

            fun toSubDir(category: Category): String = when (category) {
                MAIN_SKILLS -> "main"
                EXECUTOR_SKILLS -> "executor"
                AUTO_SKILLS -> "" // auto-skills 下直接放技能目录
            }
        }
    }

    /** 技能模型 */
    data class Skill(
        val name: String,
        val description: String,
        val category: Category,
        val path: String,       // 相对 skillsRoot 的路径
        val content: String = ""
    )

    /**
     * 初始化技能管理器
     * @param skillsDir agent/skills 目录的绝对路径
     */
    fun init(skillsDir: String) {
        skillsRoot = File(skillsDir)
        skillsRoot?.mkdirs()
        // 确保子目录存在
        Category.entries.forEach { cat ->
            File(skillsRoot, cat.dirName).mkdirs()
        }
        Log.i(TAG, "SkillManager initialized: $skillsDir")
    }

    fun init(context: Context) {
        val runtimeDir = com.pocketagent.app.update.CodeSyncManager.getInstance().getRuntimeDir()
        init(File(runtimeDir, "agent/skills").absolutePath)
    }

    private fun getSkillsRoot(): File =
        skillsRoot ?: throw IllegalStateException("SkillManager not initialized")

    // ─── 读取 ─────────────────────────────────────

    /**
     * 获取指定分类下的所有技能
     */
    fun getSkills(category: Category): List<Skill> {
        val dir = File(getSkillsRoot(), category.dirName)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { subDir ->
                // auto-skills 下有一层子分类目录（executor/main），再往下才是技能
                if (category == Category.AUTO_SKILLS) {
                    subDir.listFiles()
                        ?.filter { it.isDirectory }
                        ?.mapNotNull { skillDir -> readSkill(skillDir, category) }
                        ?: emptyList()
                } else {
                    // main/executor 技能可能有多层嵌套
                    listOfNotNull(readSkill(subDir, category))
                }
            }
            ?: emptyList()
    }

    /**
     * 递归查找技能目录（支持多层嵌套）
     */
    private fun readSkill(dir: File, category: Category): Skill? {
        // 先检查本级有没有 SKILL.md
        val skillFile = findSkillFile(dir)
        if (skillFile != null) {
            val content = skillFile.readText()
            val (name, description) = parseFrontmatter(content)
            val relativePath = dir.relativeTo(getSkillsRoot()).path
            return Skill(
                name = name ?: dir.name,
                description = description ?: "",
                category = category,
                path = relativePath,
                content = content
            )
        }

        // 没有 SKILL.md，可能是中间目录，递归查找子目录
        for (child in dir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            val found = readSkill(child, category)
            if (found != null) return found
        }

        return null
    }

    /**
     * 获取全部分类下的所有技能
     */
    fun getAllSkills(): List<Skill> =
        Category.entries.flatMap { getSkills(it) }

    /**
     * 根据路径获取单个技能详情
     */
    fun getSkillByPath(path: String): Skill? {
        val file = File(getSkillsRoot(), path)
        if (!file.isDirectory) return null
        val skillFile = findSkillFile(file) ?: return null
        val content = skillFile.readText()
        val (name, description) = parseFrontmatter(content)
        val category = Category.fromDirName(path.substringBefore("/"))
            ?: return null
        return Skill(
            name = name ?: file.name,
            description = description ?: "",
            category = category,
            path = path,
            content = content
        )
    }

    // ─── 创建（仅 auto-skills）─────────────────────

    /**
     * 创建新技能（仅允许 auto-skills）
     *
     * @param name 技能名称（用作目录名，全小写+连字符）
     * @param description 技能描述
     * @param content SKILL.md 正文
     * @param category 目标分类（auto-skills 下的子分类：main/executor）
     * @return 创建成功后的 Skill，失败返回 null
     */
    fun createSkill(
        name: String,
        description: String,
        content: String,
        category: Category = Category.AUTO_SKILLS
    ): Skill? {
        if (category.isSystem) {
            Log.w(TAG, "Cannot create skill in system category: $category")
            return null
        }

        val subDir = Category.toSubDir(category)
        val skillDir = if (subDir.isNotEmpty()) {
            File(File(getSkillsRoot(), category.dirName), "$subDir/$name")
        } else {
            File(File(getSkillsRoot(), category.dirName), name)
        }

        if (skillDir.exists()) {
            Log.w(TAG, "Skill already exists: $name")
            return null
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
        skillFile.writeText(frontmatter)

        val relativePath = skillDir.relativeTo(getSkillsRoot()).path
        return Skill(name, description, category, relativePath, frontmatter)
    }

    // ─── 更新（仅 auto-skills）─────────────────────

    /**
     * 更新技能内容（仅允许 auto-skills）
     */
    fun updateSkill(path: String, name: String, description: String, content: String): Boolean {
        val category = Category.fromDirName(path.substringBefore("/")) ?: return false
        if (category.isSystem) return false

        val skillDir = File(getSkillsRoot(), path)
        if (!skillDir.isDirectory) return false

        val skillFile = findSkillFile(skillDir) ?: File(skillDir, "SKILL.md")
        val frontmatter = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: $description")
            appendLine("---")
            appendLine()
            appendLine(content.trim())
        }
        skillFile.writeText(frontmatter)
        return true
    }

    // ─── 删除（仅 auto-skills）─────────────────────

    /**
     * 删除技能（仅允许 auto-skills）
     */
    fun deleteSkill(path: String): Boolean {
        val category = Category.fromDirName(path.substringBefore("/")) ?: return false
        if (category.isSystem) return false

        val skillDir = File(getSkillsRoot(), path)
        if (!skillDir.isDirectory) return false

        return skillDir.deleteRecursively()
    }

    // ─── 内部工具 ─────────────────────────────────

    /** 在目录中查找 SKILL.md（支持大小写变体） */
    private fun findSkillFile(dir: File): File? {
        for (name in listOf("SKILL.md", "skill.md", "Skill.md")) {
            val file = File(dir, name)
            if (file.exists() && file.isFile) return file
        }
        return null
    }

    /**
     * 解析 YAML frontmatter，提取 name 和 description
     * 格式：
     *   ---
     *   name: skill-name
     *   description: 技能描述
     *   ---
     */
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
            val value = line.substring(colonIndex + 1).trim()
            when (key) {
                "name" -> name = value
                "description" -> description = value
            }
        }

        return name to description
    }

    /**
     * 获取技能文件内容的正文部分（去掉 frontmatter）
     */
    fun getContentBody(content: String): String {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return trimmed
        val endIndex = trimmed.indexOf("---", 3)
        if (endIndex < 0) return trimmed
        return trimmed.substring(endIndex + 3).trim()
    }
}

package com.pocketagent.app.ui.screens.skills

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.navigation.NavController
import com.pocketagent.app.core.SkillManager
import com.pocketagent.app.ui.theme.GlassCard
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    var skills by remember { mutableStateOf<List<SkillManager.Skill>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // 详情视图状态
    var selectedSkill by remember { mutableStateOf<SkillManager.Skill?>(null) }

    // 拦截系统返回手势：在详情视图时回到列表，而非退到首页
    BackHandler(enabled = selectedSkill != null) {
        selectedSkill = null
    }

    // 技能说明弹窗
    var showDescriptionDialog by remember { mutableStateOf<SkillManager.Skill?>(null) }

    // 新建/编辑对话框状态
    var showSkillDialog by remember { mutableStateOf(false) }
    var editSkill by remember { mutableStateOf<SkillManager.Skill?>(null) }

    // 删除确认
    var showDeleteConfirm by remember { mutableStateOf<SkillManager.Skill?>(null) }

    // 导出状态
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var exporting by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 当前分类
    val currentCategory = when (selectedTab) {
        0 -> SkillManager.Category.MAIN_SKILLS
        1 -> SkillManager.Category.EXECUTOR_SKILLS
        2 -> SkillManager.Category.AUTO_SKILLS
        else -> SkillManager.Category.MAIN_SKILLS
    }

    // 加载技能
    LaunchedEffect(selectedTab) {
        isLoading = true
        skills = SkillManager.getSkills(currentCategory)
        isLoading = false
    }

    // 单技能导出
    fun exportSingle(skill: SkillManager.Skill) {
        scope.launch {
            exporting = true
            val dest = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "PocketAgent_Skills"
            ).also { it.mkdirs() }
            val result = SkillManager.exportSkill(skill.path, dest.absolutePath)
            if (result != null) {
                snackbarHostState.showSnackbar("已导出: ${skill.name}")
            } else {
                snackbarHostState.showSnackbar("导出失败: ${skill.name}")
            }
            exporting = false
        }
    }

    // 批量导出
    fun exportBatch() {
        scope.launch {
            exporting = true
            val dest = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "PocketAgent_Skills"
            ).also { it.mkdirs() }
            val selectedCount = selectedPaths.size
            val exported = SkillManager.batchExportSkills(selectedPaths.toList(), dest.absolutePath)
            isSelectionMode = false
            selectedPaths = emptySet()
            exporting = false
            val msg = if (exported.size == selectedCount) {
                "已导出 ${exported.size} 个技能到 Downloads/PocketAgent_Skills/"
            } else {
                "导出完成: ${exported.size}/${selectedCount} 个成功"
            }
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isSelectionMode) "选择技能" else "技能库")
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        TextButton(onClick = {
                            isSelectionMode = false
                            selectedPaths = emptySet()
                        }) {
                            Text("取消", fontSize = 16.sp)
                        }
                    } else if (selectedSkill != null) {
                        TextButton(onClick = { selectedSkill = null }) {
                            Text("← 返回", fontSize = 16.sp)
                        }
                    } else {
                        TextButton(onClick = { navController.popBackStack() }) {
                            Text("← 返回", fontSize = 16.sp)
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        if (selectedPaths.isNotEmpty()) {
                            TextButton(
                                onClick = { exportBatch() },
                                enabled = !exporting
                            ) {
                                if (exporting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("导出 (${selectedPaths.size})", fontSize = 14.sp)
                                }
                            }
                        }
                    } else if (selectedSkill == null) {
                        if (skills.isNotEmpty()) {
                            IconButton(onClick = { isSelectionMode = true }) {
                                Icon(Icons.Default.FileDownload, contentDescription = "批量导出")
                            }
                        }
                        IconButton(onClick = {
                            scope.launch {
                                isLoading = true
                                skills = SkillManager.getSkills(currentCategory)
                                isLoading = false
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                        TextButton(onClick = {
                            editSkill = null
                            showSkillDialog = true
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "新建技能", modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("新建", fontSize = 14.sp)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (selectedSkill != null) {
            // ─── 详情视图 ───
            SkillDetailView(
                skill = selectedSkill!!,
                onBack = { selectedSkill = null },
                onEdit = {
                    editSkill = selectedSkill
                    showSkillDialog = true
                },
                onDelete = {
                    showDeleteConfirm = selectedSkill
                },
                onExport = { exportSingle(selectedSkill!!) }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 分类标签
                TabRow(selectedTabIndex = selectedTab) {
                    SkillManager.Category.entries.forEachIndexed { index, category ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(category.displayName, fontSize = 14.sp) }
                        )
                    }
                }

                // 技能列表
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (skills.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "暂无技能",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Text(
                                "点击右上角「新建」添加新技能",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(skills, key = { it.path }) { skill ->
                            SkillCard(
                                skill = skill,
                                canDelete = !skill.category.isSystem,
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedPaths.contains(skill.path),
                                onToggleSelect = {
                                    selectedPaths = if (selectedPaths.contains(skill.path)) {
                                        selectedPaths - skill.path
                                    } else {
                                        selectedPaths + skill.path
                                    }
                                },
                                onClick = {
                                    showDescriptionDialog = skill
                                },
                                onViewDetail = {
                                    selectedSkill = skill
                                },
                                onDelete = {
                                    showDeleteConfirm = skill
                                },
                                onExport = { exportSingle(skill) }
                            )
                        }
                    }
                }
            }
        }
    }

    // ─── 新建/编辑对话框 ───
    if (showSkillDialog) {
        SkillEditDialog(
            skill = editSkill,
            category = currentCategory,
            onDismiss = {
                showSkillDialog = false
                editSkill = null
            },
            onSave = { name, description, content ->
                scope.launch {
                    if (editSkill != null) {
                        SkillManager.updateSkill(editSkill!!.path, name, description, content)
                    } else {
                        SkillManager.createSkill(name, description, content, currentCategory)
                    }
                    showSkillDialog = false
                    editSkill = null
                    skills = SkillManager.getSkills(currentCategory)
                }
            }
        )
    }

    // ─── 技能说明弹窗 ───
    if (showDescriptionDialog != null) {
        SkillDescriptionDialog(
            skill = showDescriptionDialog!!,
            onDismiss = { showDescriptionDialog = null },
            onViewDetail = {
                selectedSkill = showDescriptionDialog!!
                showDescriptionDialog = null
            }
        )
    }

    // ─── 删除确认 ───
    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("删除技能") },
            text = {
                Text("确定删除「${showDeleteConfirm!!.name}」吗？\n此操作不可撤销。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            SkillManager.deleteSkill(showDeleteConfirm!!.path)
                            showDeleteConfirm = null
                            skills = SkillManager.getSkills(currentCategory)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }
}

// ─── 技能卡片 ───────────────────────────────────

@Composable
private fun SkillCard(
    skill: SkillManager.Skill,
    canDelete: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onViewDetail: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!isSelectionMode) Modifier.clickable { onClick() }
                else Modifier
            ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 选择模式复选框
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // 图标
            Icon(
                imageVector = when {
                    skill.category == SkillManager.Category.MAIN_SKILLS -> Icons.Default.Code
                    skill.category == SkillManager.Category.EXECUTOR_SKILLS -> Icons.Default.Build
                    else -> Icons.Default.AutoAwesome
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.width(12.dp))

            // 文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (skill.description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = skill.description,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 2
                    )
                }
            }

            // 操作按钮
            Row {
                if (!isSelectionMode) {
                    IconButton(onClick = onViewDetail) {
                        Icon(Icons.Default.Visibility, contentDescription = "查看", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.FileDownload, contentDescription = "导出", modifier = Modifier.size(20.dp))
                    }
                    if (canDelete) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── 详情视图 ───────────────────────────────────

@Composable
private fun SkillDetailView(
    skill: SkillManager.Skill,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    val body = remember(skill) { SkillManager.getContentBody(skill.content) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 顶部栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = skill.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (skill.description.isNotBlank()) {
                        Text(
                            text = skill.description,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Row {
                if (skill.category.isSystem) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "系统技能 · 只读",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                IconButton(onClick = onExport) {
                    Icon(Icons.Default.FileDownload, contentDescription = "导出")
                }
                if (!skill.category.isSystem) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 标签
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {},
                label = { Text(skill.category.displayName, fontSize = 11.sp) }
            )
            AssistChip(
                onClick = {},
                label = { Text(skill.path, fontSize = 11.sp) }
            )
        }

        Spacer(Modifier.height(16.dp))

        // 内容（Markdown 渲染）
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (body.isNotBlank()) {
                    MarkdownText(
                        markdown = body,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = "(空)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

// ─── 新建/编辑对话框 ───────────────────────────

@Composable
private fun SkillEditDialog(
    skill: SkillManager.Skill?,
    category: SkillManager.Category,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, content: String) -> Unit
) {
    val isEdit = skill != null
    val initialName = remember(skill) { skill?.name ?: "" }
    val initialDesc = remember(skill) { skill?.description ?: "" }
    val initialContent = remember(skill) { SkillManager.getContentBody(skill?.content ?: "") }

    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDesc) }
    var content by remember { mutableStateOf(initialContent) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑技能" else "新建技能 — ${category.displayName}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; warnings = emptyList() },
                    label = { Text("技能名称") },
                    placeholder = { Text("如: phone-control") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isEdit
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it; warnings = emptyList() },
                    label = { Text("技能描述") },
                    placeholder = { Text("一句话描述技能用途") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it; warnings = emptyList() },
                    label = { Text("SKILL.md 内容") },
                    placeholder = {
                        Text("---\nname: ${if (name.isNotBlank()) name else "技能名称"}\ndescription: 技能描述\n---\n\n## 任务目标\n...\n\n## 执行步骤\n...")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                )

                // 格式校验警告
                if (warnings.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            warnings.forEach { warning ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("⚠", fontSize = 12.sp)
                                    Text(
                                        text = warning,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val validation = SkillManager.validateSkillFormat(name, description, content)
                    if (!validation.valid || validation.warnings.isNotEmpty()) {
                        warnings = validation.warnings
                    } else {
                        onSave(name, description, content)
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text(if (isEdit) "保存" else "创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ─── 技能说明弹窗 ───────────────────────────────

@Composable
private fun SkillDescriptionDialog(
    skill: SkillManager.Skill,
    onDismiss: () -> Unit,
    onViewDetail: () -> Unit
) {
    val isAuto = skill.category == SkillManager.Category.AUTO_SKILLS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = when (skill.category) {
                            SkillManager.Category.MAIN_SKILLS -> Icons.Default.Code
                            SkillManager.Category.EXECUTOR_SKILLS -> Icons.Default.Build
                            SkillManager.Category.AUTO_SKILLS -> Icons.Default.AutoAwesome
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(skill.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.height(4.dp))
                AssistChip(
                    onClick = {},
                    label = { Text(skill.category.displayName, fontSize = 11.sp) }
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (skill.description.isNotBlank()) {
                    Text(
                        text = skill.description,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                if (isAuto) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "此技能由 Agent 在完成一次任务后自动沉淀生成，总结执行经验以便后续复用。你可以直接使用或按需编辑。",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onViewDetail) {
                Text("查看详情")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

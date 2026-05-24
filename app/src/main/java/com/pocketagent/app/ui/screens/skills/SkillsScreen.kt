package com.pocketagent.app.ui.screens.skills

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.pocketagent.app.core.SkillManager
import com.pocketagent.app.ui.theme.GlassCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(navController: NavController) {
    var selectedTab by remember { mutableStateOf(0) }
    var skills by remember { mutableStateOf<List<SkillManager.Skill>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // 详情视图状态
    var selectedSkill by remember { mutableStateOf<SkillManager.Skill?>(null) }

    // 新建/编辑对话框状态
    var showSkillDialog by remember { mutableStateOf(false) }
    var editSkill by remember { mutableStateOf<SkillManager.Skill?>(null) }

    // 删除确认
    var showDeleteConfirm by remember { mutableStateOf<SkillManager.Skill?>(null) }

    // 加载技能
    LaunchedEffect(selectedTab) {
        isLoading = true
        val category = when (selectedTab) {
            0 -> SkillManager.Category.MAIN_SKILLS
            1 -> SkillManager.Category.EXECUTOR_SKILLS
            2 -> SkillManager.Category.AUTO_SKILLS
            else -> SkillManager.Category.MAIN_SKILLS
        }
        skills = SkillManager.getSkills(category)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("技能库") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text("← 返回", fontSize = 16.sp)
                    }
                },
                actions = {
                    if (selectedTab == 2 && selectedSkill == null) {
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
                }
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
                                Icons.Default.Lightbulb,
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
                            if (selectedTab == 2) {
                                Text(
                                    "点击右上角「新建」添加自动技能",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }
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
                                onClick = { selectedSkill = skill },
                                onDelete = {
                                    showDeleteConfirm = skill
                                }
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
            onDismiss = {
                showSkillDialog = false
                editSkill = null
            },
            onSave = { name, description, content ->
                if (editSkill != null) {
                    SkillManager.updateSkill(editSkill!!.path, name, description, content)
                } else {
                    SkillManager.createSkill(name, description, content)
                }
                showSkillDialog = false
                editSkill = null
                // 刷新列表
                selectedTab = 2
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
                        SkillManager.deleteSkill(showDeleteConfirm!!.path)
                        showDeleteConfirm = null
                        // 刷新列表
                        skills = SkillManager.getSkills(SkillManager.Category.AUTO_SKILLS)
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
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
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
                IconButton(onClick = onClick) {
                    Icon(Icons.Default.Visibility, contentDescription = "查看", modifier = Modifier.size(20.dp))
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

// ─── 详情视图 ───────────────────────────────────

@Composable
private fun SkillDetailView(
    skill: SkillManager.Skill,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
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
                } else {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "系统技能",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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

        // 内容
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (body.isNotBlank()) body else "(空)",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ─── 新建/编辑对话框 ───────────────────────────

@Composable
private fun SkillEditDialog(
    skill: SkillManager.Skill?,
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
    var selectedSubCategory by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑技能" else "新建技能") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("技能名称") },
                    placeholder = { Text("如: phone-control") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isEdit
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("技能描述") },
                    placeholder = { Text("一句话描述技能用途") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!isEdit) {
                    Text("目标分类", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Main" to "主技能", "Executor" to "执行技能").forEachIndexed { i, (_, label) ->
                            FilterChip(
                                selected = selectedSubCategory == i,
                                onClick = { selectedSubCategory = i },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("SKILL.md 内容") },
                    placeholder = { Text("## 任务目标\n...\n\n## 执行步骤\n...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, description, content) },
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

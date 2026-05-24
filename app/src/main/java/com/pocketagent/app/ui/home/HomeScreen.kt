package com.pocketagent.app.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.pocketagent.app.ui.components.AnimatedBackground
import com.pocketagent.app.ui.theme.*

private data class NavEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String
)

private val navEntries = listOf(
    NavEntry("执行任务", "用 AI 操控手机，一步到位", Icons.Default.PlayArrow, "execute"),
    NavEntry("历史记录", "查看过往任务与执行结果", Icons.Default.History, "history"),
    NavEntry("技能库", "Agent 已掌握的技能与工具", Icons.Default.Psychology, "skills"),
    NavEntry("终端", "命令行与调试控制台", Icons.Default.Terminal, "terminal"),
    NavEntry("设置", "模型 / MCP / 高级参数", Icons.Default.Settings, "config"),
    NavEntry("悬浮窗", "后台执行与状态监控", Icons.Default.PictureInPicture, "overlay"),
)

@Composable
fun HomeScreen(navController: NavController, modelConfigured: Boolean) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        // ─── 液态玻璃动态背景 ───
        AnimatedBackground(
            orbColors = orbColors(),
            modifier = Modifier.fillMaxSize()
        )

        // ─── 前景内容 ───
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(48.dp))

            // 标题区域（渐入动画）
            AnimatedStaggeredItem(delayMs = 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = "Pocket Agent",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.3f).sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "口袋里的 AI 管家",
                            fontSize = 13.sp,
                            letterSpacing = (0.3f).sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Dreamt0511"))
                                context.startActivity(intent)
                            }
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF24292F),
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "GH",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                        Text(
                            text = "作者主页",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // ─── 导航项列表（错峰入场） ───
            navEntries.forEachIndexed { index, entry ->
                AnimatedStaggeredItem(delayMs = 100 + index * 80) {
                    PressBounceContainer(onClick = { navController.navigate(entry.route) }) {
                        NavCard(entry = entry)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // 底部 Agent 状态
            if (modelConfigured) {
                Spacer(Modifier.height(8.dp))
                AnimatedStaggeredItem(delayMs = 100 + navEntries.size * 80) {
                    AgentStatusBadge()
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── 导航卡片 ──────────────────────────────────

@Composable
private fun NavCard(entry: NavEntry) {
    GlassCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标圆形容器
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(navIconBackground()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.width(14.dp))

            // 文字区域
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (0.2f).sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = entry.subtitle,
                    fontSize = 12.5.sp,
                    letterSpacing = (0.1f).sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            // 箭头指示
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            )
        }
    }
}

// ─── Agent 就绪徽章 ────────────────────────────

@Composable
private fun AgentStatusBadge() {
    GlassSurface(shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // 绿点指示灯
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Agent 就绪",
                fontSize = 12.5.sp,
                letterSpacing = (0.3f).sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                textAlign = TextAlign.Center
            )
        }
    }
}

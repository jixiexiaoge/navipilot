package com.example.navipilot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navipilot.ui.utils.localized

private data class FAQItem(val question: String, val answer: String)

@Composable
fun HelpPage(deviceIP: String? = null) {
    val context = LocalContext.current
    var isFAQExpanded by remember { mutableStateOf(true) }
    var showC3ManagerBrowser by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text(
            text = localized("帮助中心", "Help Center"),
            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 管理器按钮
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (deviceIP != null && deviceIP.isNotEmpty()) {
                            showC3ManagerBrowser = true
                        } else {
                            android.widget.Toast.makeText(context,
                                localized("⚠️ 未检测到Comma3设备\n请确保设备已连接", "⚠️ Comma3 device not detected\nPlease ensure the device is connected"),
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = localized("管理器", "Manager"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // FAQ 卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { isFAQExpanded = !isFAQExpanded }.padding(bottom = 16.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = localized("常见问题", "FAQ"), tint = Color(0xFF10B981), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = localized("常见问题", "FAQ"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B), modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = if (isFAQExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isFAQExpanded) localized("收起", "Collapse") else localized("展开", "Expand"),
                        tint = Color(0xFF64748B), modifier = Modifier.size(24.dp)
                    )
                }
                if (isFAQExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        faqItems().forEach { faq -> FAQItemCard(faq = faq) }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showC3ManagerBrowser && deviceIP != null && deviceIP.isNotEmpty()) {
        FullscreenBrowserDialog(onDismiss = { showC3ManagerBrowser = false }, url = "http://$deviceIP:7000", title = localized("管理器", "Manager"))
    }
}

@Composable
private fun FAQItemCard(faq: FAQItem) {
    var isExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded }.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = localized("问题", "Question"), tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = faq.question, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E293B), modifier = Modifier.weight(1f), lineHeight = 20.sp)
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) localized("收起", "Collapse") else localized("展开", "Expand"),
                    tint = Color(0xFF64748B), modifier = Modifier.size(20.dp)
                )
            }
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = faq.answer, fontSize = 14.sp, color = Color(0xFF475569), lineHeight = 20.sp)
            }
        }
    }
}

private fun faqItems(): List<FAQItem> = listOf(
    FAQItem(
        localized("国际版有何不同？", "What makes the International Edition different?"),
        localized(
            "核心优势：① 国内配合高德车机版使用 ② 海外支持OSM地图 ③ 国内高德/腾讯搜索，海外谷歌搜索 ④ 车道级定位提醒 ⑤ 中英文自动切换",
            "Key advantages: ① Works with Amap Auto in China ② OSM maps overseas ③ Amap/Tencent search in China, Google abroad ④ Lane-level positioning ⑤ Auto language switching"
        )
    ),
    FAQItem(
        localized("CarrotAmap 和 CP搭子有什么区别？", "CarrotAmap vs NaviPilot?"),
        localized(
            "CP搭子是 CarrotAmap 的升级版，稳定性、体验和更新都更完善。CarrotAmap 已停维，CP搭子目前仅对赞助用户开放。",
            "NaviPilot is the upgraded version of CarrotAmap (no longer maintained). It improves stability and usability, currently available to sponsors only."
        )
    ),
    FAQItem(
        localized("支持哪些车型？", "Supported car models?"),
        localized(
            "除 OpenPilot 官方 300+ 车型外，国内额外适配了大量比亚迪及热门国产车型。具体请以官方兼容列表为准。",
            "Besides OpenPilot's 300+ official models, many BYD and popular Chinese models are also adapted. Check the official compatibility list."
        )
    ),
    FAQItem(
        localized("为什么选择 CP 而不是 SP、DP 或 FP？", "Why CP over SP/DP/FP?"),
        localized(
            "CP 是最早实现外挂导航辅助驾驶（NOO）的项目之一，导航集成经验丰富，代码开源、方案成熟，参考价值高。",
            "CP is one of the earliest NOO projects, with deep navigation integration experience, open-source code, and a mature solution."
        )
    ),
    FAQItem(
        localized("只能用高德车机版吗？", "Only Amap Auto?"),
        localized(
            "目前高德车机版适配最完整。其他导航应用技术上可接，但暂未适配。",
            "Amap Auto is the most complete option. Other navigation apps are technically possible but not yet adapted."
        )
    ),
    FAQItem(
        localized("为什么不是所有车都能自动超车？", "Why not all cars support auto-overtake?"),
        localized(
            "核心是安全问题。不同车型传感器差异大，部分缺少盲区监测，无法确保变道安全，必须逐车型验证。",
            "Safety is the key. Vehicles have different sensor configs — some lack blind-spot monitoring, so each model must be individually validated."
        )
    ),
    FAQItem(
        localized("为什么需要赞助才能使用？", "Why sponsorship required?"),
        localized(
            "项目初衷非盈利，但服务器、维护、支持需要持续投入。用户规模扩大后个人难以承担，赞助模式用于控制规模、保证质量。",
            "The project isn't for profit, but servers, maintenance, and support cost resources. Sponsorship keeps it sustainable as the user base grows."
        )
    ),
    FAQItem(
        localized("CP搭子国际版有哪些变化？", "What's new in the International Edition?"),
        localized(
            "三大调整：① 精简本地化功能 ② 强化首页布局和地图展示 ③ 国内高德/海外谷歌导航方案。界面根据语言自动切换中英文。",
            "Three changes: ① Simplified localization ② Better home layout & map display ③ Region-based navigation: Amap in China, Google abroad. UI auto-switches language."
        )
    ),
    FAQItem(
        localized("App 定位模式是什么意思？", "What do positioning modes mean?"),
        localized(
            "系统自动选择可用的最高精度方案：GPS(3-15m) → SBAS(2-5m) → L1+L5(1-3m) → L1L5+S(1-2m) → DGNSS(1-2m) → RTK(厘米级)。开阔环境效果最好。",
            "The system picks the best available: GPS(3-15m) → SBAS(2-5m) → L1+L5(1-3m) → L1L5+S(1-2m) → DGNSS(1-2m) → RTK(cm-level). Open areas work best."
        )
    ),
    FAQItem(
        localized("为什么没有 iPhone 版本？", "Why no iPhone version?"),
        localized(
            "iOS 需分别适配国内外地图 SDK，开发和维护成本高，目前无计划。欢迎开发者基于现有思路探索。",
            "iOS would need separate map SDK integrations, making development expensive. No active plans, but developers are welcome to explore."
        )
    )
)

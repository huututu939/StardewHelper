package com.stardew.assistant.ui.mods

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.Crop
import com.stardew.assistant.data.ModRepo
import com.stardew.assistant.ui.FramedIcon
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.slotBevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **画一个作物图标，单源入口**：包给了图就用包里的，否则走原版图标框。
 *
 * 作物图标现在有 4 个消费点（图鉴格子 / 图鉴详情 / 计算页露大结果行 / 计算页温室循环行）。
 * 每处各自判一遍「modId 与 iconPath 有没有值」的话，加第 5 个消费点时漏判是必然的，
 * 而漏判的表现是「那个界面里 SVE 作物没图」——不报错、不崩，只是少了张图。
 * 所以判据收在这一个函数里，调用方只管把 [Crop] 递进来。
 *
 * @param crop 作物对象（[ModCropIcon] 那版要自己传 name/modId/rel 三件套，是因为
 *   图鉴那边手上只有 [com.stardew.assistant.data.CodexItem]，得先按名反查一次）
 * @param size 显示边长（dp）
 * @param pad 槽内边距，与 [FramedIcon] 同参数名，便于视觉对齐
 */
@Composable
fun CropIcon(crop: Crop, size: Int, pad: Int = 2) {
    val modId = crop.modId
    val rel = crop.iconPath
    if (modId != null && rel != null) {
        ModCropIcon(crop.name, modId, rel, size, pad)
    } else {
        // 原版作物 / 老包（v2.4 及更早没有 icon 字段）→ 原版图标框
        FramedIcon(crop.name, size, pad)
    }
}

/**
 * v2.73.5 作物图标：**Mod 包给了图就用包里的，否则走原版图标框**。
 *
 * 与 [VillagerAvatar] 同款结构（先同步 peek、miss 再 IO 线程解码、失败退回原版框），
 * 差别只在解码规则：作物走 [ModRepo.CropIcons]（末个非空帧），头像走
 * [ModRepo.Portraits]（64 见方取第一格）。
 *
 * ⚠ 一般调用方请走 [CropIcon]（按 Crop 分发）。直接调这个函数只在手上**只有
 *   name/modId/rel 三件套、没有 Crop 对象**时才需要——图鉴那边就是这样
 *   （手上是 CodexItem，得先按名反查）。
 *
 * ## 为什么不把「有没有图」塞进 FramedIcon
 * 同 [VillagerAvatar] 的理由：FramedIcon 全项目 20 多处调用，签名里只有 name，
 * 而「这个 name 来自哪个包、图路径是什么」不在参数里。作物图标位有限且都是作物，
 * 收在 [CropIcon] 即可，不必让所有图标位（物品、鱼…）都多一次查表。
 *
 * @param name 条目名（解码失败时退回原版图标框的占位首字用）
 * @param modId 来源包 id
 * @param rel 包内相对路径（`images/crops/xxx.png`）
 * @param size 显示边长（dp）
 * @param pad 槽内边距，与 [FramedIcon] 同参数名，便于视觉对齐
 */
@Composable
fun ModCropIcon(name: String, modId: String, rel: String, size: Int, pad: Int = 2) {
    val c = LocalPixel.current
    val ctx = LocalContext.current
    var bmp by remember(name, modId, rel) {
        mutableStateOf(ModRepo.CropIcons.peek(modId, rel))
    }
    LaunchedEffect(name, modId, rel) {
        if (bmp == null) {
            val decoded = withContext(Dispatchers.IO) { ModRepo.CropIcons.load(ctx, modId, rel) }
            if (decoded != null) bmp = decoded
        }
    }
    val b = bmp
    if (b == null) {
        // 包里没给图 / 解码失败 → 原版图标框。不显示一个破图占位符。
        FramedIcon(name, size, pad)
        return
    }
    Box(
        Modifier
            .background(c.slot)
            .slotBevel(inset = 2.dp, width = 1.dp)
            .border(2.dp, c.slotEdge)
            .padding(pad.dp),
    ) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = name,
            modifier = Modifier.size(size.dp),
            // 像素素材用不着双线性：与 AssetImage / FramedIcon / VillagerAvatar 同款。
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    }
}

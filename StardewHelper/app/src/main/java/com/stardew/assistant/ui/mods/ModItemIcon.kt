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
import com.stardew.assistant.data.ModRepo
import com.stardew.assistant.ui.FramedIcon
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.slotBevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v2.73.8 包内**单张物品图**（料理图标用）。
 *
 * 与 [ModCropIcon] 同款结构（先同步 peek、miss 再 IO 线程解码、失败退回原版框），
 * 差别在解码规则：料理图走 [ModRepo.ItemIcons]（**单图不裁帧**，只做整数倍归一化），
 * 作物图走 [ModRepo.CropIcons]（sprite sheet，要裁出末个非空帧）。
 *
 * ⚠ 一般调用方请走 [CropIcon] 之外的 **按需**判断：包内图存在与否在数据层判
 *   （`ModRecipe.icon`），调用方只管「有图走这里、没图走 FramedIcon」。
 *
 * @param name 条目名（解码失败时退回原版图标框的占位首字用）
 * @param modId 来源包 id
 * @param rel 包内相对路径（`images/recipes/xxx.png`）
 * @param size 显示边长（dp）
 * @param pad 槽内边距，与 [FramedIcon] 同参数名，便于视觉对齐
 */
@Composable
fun ModItemIcon(name: String, modId: String, rel: String, size: Int, pad: Int = 2) {
    val c = LocalPixel.current
    val ctx = LocalContext.current
    var bmp by remember(name, modId, rel) {
        mutableStateOf(ModRepo.ItemIcons.peek(modId, rel))
    }
    LaunchedEffect(name, modId, rel) {
        if (bmp == null) {
            val decoded = withContext(Dispatchers.IO) { ModRepo.ItemIcons.load(ctx, modId, rel) }
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

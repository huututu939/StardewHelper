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
import com.stardew.assistant.data.GameData
import com.stardew.assistant.data.ModRepo
import com.stardew.assistant.ui.FramedIcon
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.slotBevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v2.72 村民头像：**Mod 包带头像就用包里的，否则走原版图标框**。
 *
 * 为什么不直接改 [FramedIcon]：它全项目 20 多处调用，签名里只有 `name`，
 * 而「这个 name 对应的人来自哪个包、头像路径是什么」不在参数里——
 * 在 [FramedIcon] 内部按 name 反查 GameData 会让**所有**图标位（物品、作物、鱼…）
 * 都多一次查表，而那 20 多处绝大多数是物品不是人。故另开一个只给人用的入口。
 *
 * 取图分两步（与 [AssetImage] 同款冷启动优化）：先同步命中缓存，
 * miss 才在 IO 线程解码——组合期主线程读盘会让首次滚动掉帧。
 *
 * @param size 显示边长（dp）
 * @param pad 槽内边距，与 [FramedIcon] 同参数名，便于视觉对齐
 */
@Composable
fun VillagerAvatar(name: String, size: Int, pad: Int = 2) {
    val c = LocalPixel.current
    val ctx = LocalContext.current
    // v 是 `gifts` 里的那一条；`GameData.modPacks` 进 key 是因为开关一换，
    // 这个人的来源就可能从「无」变成「某个包」，头像也得跟着换。
    val v = remember(name, GameData.modPacks, GameData.modVillagerCount) {
        GameData.gifts.firstOrNull { it.name == name }
    }
    val modId = v?.modId
    val rel = v?.portraitPath
    var bmp by remember(name, modId, rel) {
        mutableStateOf(
            if (modId != null && rel != null) ModRepo.Portraits.peek(modId, rel) else null,
        )
    }
    LaunchedEffect(name, modId, rel) {
        if (bmp == null && modId != null && rel != null) {
            val decoded = withContext(Dispatchers.IO) { ModRepo.Portraits.load(ctx, modId, rel) }
            if (decoded != null) bmp = decoded
        }
    }
    val b = bmp
    if (b == null) {
        // 没有头像 / 解码失败 → 原版图标框（首字占位）。不显示一个破图占位符。
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
            // 表情精灵用不着双线性：FilterQuality.None 与 AssetImage / FramedIcon 同款，
            // 放大时宁可像素硬边也不要糊成一团。
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    }
}

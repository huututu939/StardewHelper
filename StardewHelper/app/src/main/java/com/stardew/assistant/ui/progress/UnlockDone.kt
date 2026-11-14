package com.stardew.assistant.ui.progress

import com.stardew.assistant.data.GameData
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.data.ProgressStats
import com.stardew.assistant.data.bundleIsFull
import com.stardew.assistant.data.isItemChecked

/**
 * v2.25：解锁达成判定单源。
 *
 * 进度页与年历页的解锁卡都要判「这项解锁达成了吗」。两处各写一份 when 必然漂移，
 * 故抽到这里共用。
 *
 * v2.27：包内判定改调 data 层的 bundleIsFull（认「选 N」规则），
 * 这里只保留「哪个房间算完成」这一层，不再自己写 items.all。
 *
 * v2.32：钓获判定改调 data 层的 isItemChecked（打勾键单源 CodexChecks.kt）。
 * 早先它引 ui.codex 的 fishCheckKey，才被迫把整份判定留在 ui 层；现在键构造已在
 * data 层，分层顾虑消失，但仍保持原包名不动（年历页的调用点不改）。
 */
fun bundleRoomDone(roomName: String, snap: PrefsRepo.Snapshot): Boolean =
    GameData.bundleRooms.any { r ->
        val ri = GameData.bundleRooms.indexOf(r)
        r.room.contains(roomName) && r.bundles.withIndex().all { (bi, b) ->
            bundleIsFull(b, ri, bi, snap.checks)
        }
    }

/** checkKey 是否已自动达成（其余项回落到手动勾选键 "unlock:<key>"） */
fun unlockReached(key: String, st: ProgressStats, snap: PrefsRepo.Snapshot): Boolean = when (key) {
    "bundles_all" -> st.fullBundles == st.totalBundles && st.totalBundles > 0
    "museum60" -> st.museumDone >= 60
    "bundle_vault" -> bundleRoomDone("金库", snap)
    "bundle_pantry" -> bundleRoomDone("储物间", snap)
    "bundle_boiler" -> bundleRoomDone("锅炉房", snap)
    "bundle_crafts" -> bundleRoomDone("工艺房", snap)
    "buoys_all" -> GameData.fishing.all { isItemChecked("fishing", it.name, snap.checks) }
    else -> "unlock:$key" in snap.checks
}
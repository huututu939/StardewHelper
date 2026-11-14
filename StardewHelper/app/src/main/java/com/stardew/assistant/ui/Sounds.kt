package com.stardew.assistant.ui

import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.stardew.assistant.R

/** 像素 UI 音效控制器：SoundPool 播 res/raw 合成音，跟随媒体音量，零权限（v2.13）。
 *  check=勾选叮咚 / complete=完成琶音 / flip=翻页滑音 / error=失败 buzz / click=tab 咔哒(mouse_click)
 *  c215：[enabled] 进程级开关（设置页「音效」控制）。关掉后 play() 直接跳过，触觉不受影响。
 *  为什么用静态标志而不是构造参数：调用点遍布各页面（rememberSounds() 拿到的实例是共享的），
 *  在 play() 一个出口判一次 = 所有音效一齐静音，调用点零改动。 */
class PixelSounds(private val pool: SoundPool, ids: Map<String, Int>) {
    private val checkId = ids.getValue("check")
    private val completeId = ids.getValue("complete")
    private val flipId = ids.getValue("flip")
    private val errorId = ids.getValue("error")
    private val clickId = ids.getValue("click")

    /** c215 音效总开关（进程级；PrefsRepo.setSoundEnabled 同步写这里 + 落盘）。默认开 = 既有行为。 */
    companion object {
        @Volatile var enabled: Boolean = true
    }

    fun check() = play(checkId)
    fun complete() = play(completeId)
    fun flip() = play(flipId)
    fun error() = play(errorId)
    fun click() = play(clickId)

    private fun play(id: Int) {
        if (!enabled) return
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }
}

/** v2.17 E1：整 App 唯一音效池的下发通道。
 *  AppRoot 创建一次并 provide；所有页面与控件的 rememberSounds() 都会拿到同一实例——
 *  旧实现让每个 PixelCheckbox 各 new 一个 SoundPool（进度页滚动时反复分配/释放）。 */
val LocalPixelSounds = staticCompositionLocalOf<PixelSounds?> { null }

/** 整 App 共享的音效池：AppRoot 顶层调用一次（返回值同时 provide 进 Local）。
 *  其余调用点保持原写法不变——有 Local 走共享，没有才自建（兜底不崩）。 */
@Composable
fun rememberSounds(): PixelSounds = LocalPixelSounds.current ?: createSounds()

/** 实际组装一个 PixelSounds（仅供 AppRoot 建共享池用） */
@Composable
fun createSounds(): PixelSounds {
    val ctx = LocalContext.current
    val pool = remember {
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    }
    val ids = remember {
        mapOf(
            "check" to pool.load(ctx, R.raw.snd_check, 1),
            "complete" to pool.load(ctx, R.raw.snd_complete, 1),
            "flip" to pool.load(ctx, R.raw.snd_flip, 1),
            "error" to pool.load(ctx, R.raw.snd_error, 1),
            "click" to pool.load(ctx, R.raw.mouse_click, 1),
        )
    }
    DisposableEffect(Unit) { onDispose { pool.release() } }
    return remember { PixelSounds(pool, ids) }
}

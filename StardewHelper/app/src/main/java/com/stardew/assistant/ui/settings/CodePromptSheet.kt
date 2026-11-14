package com.stardew.assistant.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.SaveReceiver
import com.stardew.assistant.ui.DetailDrawer
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px

/**
 * v2.75.0：验证码输入弹窗。c232 从 SettingsPage 原样搬出（正文一字未动），
 * 因为自动监听起来后配对请求可能出现在**任何页面**——码框必须挂在全局宿主
 * （MainActivity）上，不能只活在设置页里。
 *
 * ## 输入只收数字
 * `onValueChange` 里 `filter { it.isDigit() }.take(6)` —— 粘贴时带的空格和连字符
 * 顺手被去掉，位数也封在 6 位。
 *
 * ## 为什么不给「错误」用 Toast
 * Toast 一闪而过、还会盖住输入框，用户根本来不及看清输错没输错。
 * 这里用行内提示（`wrong` 时显示），且**不自动清空**——用户多半是差一位。
 */
@Composable
internal fun CodePromptSheet(
    attemptsLeft: Int,
    wrong: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalPixel.current
    var input by remember { mutableStateOf("") }
    DetailDrawer(
        visible = true,
        title = "核对验证码",
        onDismiss = onCancel,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PixelText("请输入电脑上显示的验证码", color = c.ink)
            PixelText(
                "电脑上那个窗口里有一行「本次验证码：482731」，照着敲 6 位数字。",
                color = c.inkDim,
            )
            Box(
                Modifier.fillMaxWidth().background(c.parchment)
                    .border(1.dp, c.slotEdge).padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                if (input.isEmpty()) PixelText("6 位数字", color = c.inkDim)
                BasicTextField(
                    value = input,
                    onValueChange = { input = it.filter { ch -> ch.isDigit() }.take(6) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = Px.body.copy(color = c.ink),
                    cursorBrush = SolidColor(c.ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (wrong) {
                PixelText("验证码不正确", color = c.red)
            }
            PixelText("还可以试 $attemptsLeft 次", color = c.inkDim)
            PixelText(
                "这个码只应该出现在你自己电脑的窗口里。" +
                    "别人发给你的码、或者让你把码念给别人的，都别照做。",
                color = c.inkDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(
                    text = "确定",
                    enabled = input.length == SaveReceiver.CODE_LEN,
                    onClick = { onSubmit(input) },
                )
                PixelButton(text = "清除", onClick = { input = "" })
                PixelButton(text = "取消", onClick = onCancel)
            }
        }
    }
}

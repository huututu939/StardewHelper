package com.stardew.assistant.ui.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.stardew.assistant.R
import com.stardew.assistant.data.FarmReport
import kotlin.math.max
import kotlin.math.min

/**
 * v2.74.2「农场年度报告」长图渲染器 —— 方向 F「鹈鹕镇特刊」。
 *
 * ## 为什么是报纸
 * 2026-10-20 的研究结论：让年度报告"高级"的最大来源不是排版多精致，
 * 而是**给整份报告一个统一的叙事装置**。星露谷游戏里本来就有《鹈鹕镇新闻》
 * （每季一期、报道农场主的成就），这是最正宗的梗——用户从 A/B/C/D 选了
 * 卡片墙之后，第二轮从 E/F/G 里选了 F。
 * 预览稿在 `报告预览/F-鹈鹕镇特刊.html`，**本文件按它 1:1 实现**，
 * 改版式先改预览稿、给用户点头，再动这里。
 *
 * ## 字号：按**手机物理尺度**定的，不是按显示器
 * 画布 1080px 宽，在 6.1 寸 1080p 手机上是 155mm 宽 → 1px = 0.1435mm。
 * 最早的正文 24px 在手机上只有 3.4mm（≈9.7pt），偏小偏碎；
 * 在 27 寸显示器上却是 5.6mm —— **预览里看着舒服，进手机缩水 1.6 倍**，
 * 这就是「预览好看 / 游戏里像另一张」的主因。
 * 所以正文用 30px（手机上 4.31mm ≈ 12.2pt，和正常中文阅读一致）。
 * 大部分字号取 12 的倍数；例外写在对应常量旁。
 *
 * ## 为什么用 Canvas 而不是 Compose
 * 长图**高度随内容变**，Compose 在无高度约束的内容里量不出真实高度，
 * 离屏图层还容易被父级裁掉。Canvas 直接画：高度自己算、想多高画多高。
 *
 * ## 线程
 * 画一张 1080×2000 的图要几十毫秒，**必须在后台线程调用**。
 */
object ReportCanvas {

    /** 图宽（像素）。高度随内容自适应。 */
    const val W = 1080

    // ── 报纸配色 ──
    private const val PAPER = 0xFFF2E8D2.toInt()   // 报纸纸
    private const val INK = 0xFF241C14.toInt()     // 墨黑
    private const val RED = 0xFFB03A2E.toInt()     // 朱红
    private const val GOLD = 0xFFC9A46A.toInt()    // 报头副色
    private const val RULE = 0xFF8A6A45.toInt()    // 细线 / 刻度
    private const val SUB = 0xFF5A4632.toInt()     // 次级文字
    private const val DIM = 0xFF8A6A45.toInt()     // 更淡的说明
    private const val TEXT = 0xFF3A2E20.toInt()    // 正文
    private const val EDIT_BG = 0xFFEDE0C4.toInt() // 编者按底色
    private const val TRACK = 0xFFDCCDB0.toInt()   // 柱状条未点亮
    private const val DOTTED = 0xFFB9A98C.toInt()  // 表格虚线
    private const val MAST_RULE = 0xFF6B4A22.toInt() // 报头分隔线

    // ── 版面（与预览稿 CSS 一一对应）──
    // .paper padding 0 48px + .col margin 0 48px → 内容区 x ∈ [96, 984]
    private const val PAPER_PAD = 48f
    private const val COL_MARGIN = 48f
    private const val COL_GAP = 32f
    private const val CONTENT_L = PAPER_PAD + COL_MARGIN          // 96
    private const val CONTENT_R = W - PAPER_PAD - COL_MARGIN     // 984
    private const val CONTENT_W = CONTENT_R - CONTENT_L          // 888
    private const val COL_W = (CONTENT_W - COL_GAP) / 2f         // 428

    // ── 字号 ──
    private const val FS_MAST = 60f      // 报头主标题
    private const val FS_SUB = 30f       // 报头副名 / 期号
    private const val FS_KICKER = 30f    // 眉题
    private const val FS_HEAD = 120f     // 头条大字
    private const val FS_DEK = 30f       // 副题
    private const val FS_COLH = 30f      // 栏标题
    private const val FS_KEY = 30f       // 表格标签
    private const val FS_VAL = 36f       // 表格数值
    /**
     * 大数值 40px。
     *
     * 不是 12 的倍数，理由是**栏宽装不下更大的**：
     * 「100 小时 25 分」在 48px 下约占 6.5em × 48 = 312px，
     * 加上左边「游玩时长」标签 4×30 = 120px，共 432px > 栏宽 428px。
     * 40px 时是 380px，刚好放得下。
     */
    private const val FS_VAL_BIG = 40f
    private const val FS_BODY = 30f      // 正文
    private const val FS_TICK = 30f      // 刻度
    private const val FS_FOOT = 30f      // 底栏

    // ── 行盒高度（= 字号 × CSS 的 line-height）──
    private const val LH_MAST = 72f      // 60 × 1.2
    private const val LH_SUB = 39f       // 30 × 1.3
    private const val LH_HEAD = 126f     // 120 × 1.05
    private const val LH_COLH = 39f      // 30 × 1.3
    private const val LH_KEY = 39f       // 30 × 1.3
    private const val LH_VAL = 47f       // 36 × 1.3
    private const val LH_BODY = 48f      // 30 × 1.6
    private const val LH_LIST = 48f
    private const val LH_FOOT = 39f

    // ── 区块内的固定高度 ──
    private const val COLH_H = 63f       // 栏标题 39 + pad 8 + 线 2 + 下边距 14
    private const val STAT_H = 68f      // 10 + 47 + 10 + 虚线 1
    private const val PER_H = 100f       // 10 + 47 + 6 + (8+18) + 10 + 虚线 1
    private const val EDIT_PAD = 18f     // 编者按上下内边距
    private const val EDIT_SIDE = 22f    // 编者按左右内边距
    private const val EDIT_BAR = 6f      // 编者按左侧朱红竖条
    private const val BAR_H = 18f        // 柱状条单格高
    private const val BAR_W = 22f        // 柱状条单格宽
    private const val BAR_GAP = 4f
    private const val BAR_CELLS = 10
    private const val CLOCK_H = 30f
    private const val CLOCK_GAP = 3f
    private const val TRACKING = 2f      // 报头字距

    /**
     * 画一张报告长图。
     *
     * @param ctx 用于取字体资源
     * @param r 存档统计
     * @param facts 文案层算出的事实（**不在这里做判断**）
     */
    fun render(ctx: Context, r: FarmReport, facts: ReportFacts): Bitmap {
        val pixelTypeface = ResourcesCompat.getFont(ctx, R.font.fusion_pixel)
            ?: Typeface.create("sans-serif", Typeface.NORMAL)

        fun paint(size: Float, color: Int, bold: Boolean = false) = Paint().apply {
            this.typeface = pixelTypeface
            textSize = size
            this.color = color
            isAntiAlias = false
            isFilterBitmap = false
            if (bold) isFakeBoldText = true
        }

        val pMast = paint(FS_MAST, PAPER)
        val pSub = paint(FS_SUB, GOLD)
        val pIssue = paint(FS_SUB, GOLD)
        val pKicker = paint(FS_KICKER, DIM)
        val pHead = paint(FS_HEAD, INK)
        val pDek = paint(FS_DEK, SUB)
        val pColH = paint(FS_COLH, RED, bold = true)
        val pKey = paint(FS_KEY, SUB)
        val pVal = paint(FS_VAL, INK)
        val pBody = paint(FS_BODY, TEXT)
        val pBold = paint(FS_BODY, INK, bold = true)
        val pTick = paint(FS_TICK, DIM)
        val pFoot = paint(FS_FOOT, DIM)

        val paper = facts.paper
        val ops = mutableListOf<Op>()

        ops += mastheadOp(paper, pMast, pSub, pIssue)
        ops += rule2Op()
        ops += kickerOp(paper, pKicker)
        ops += headlineOp(paper, pHead)
        ops += dekOp(paper, pDek)
        ops += rule3Op()
        ops += ledeOp(paper, pBody)
        ops += numbersColsOp(paper, pColH, pKey, pVal, pBody)
        ops += editorOp("编者按", paper.editorNote, pColH, pBody)
        facts.bedtime?.let { bt ->
            ops += clockOp(paper, bt, pColH, pTick, pBody, pBold)
        }
        ops += friendsColsOp(paper, pColH, pBody, pBold)
        paper.egg?.let { ops += editorOp("角落里的彩蛋", it, pColH, pBody) }
        ops += footOp(paper, pFoot)
        ops += Op(40f) { _, _ -> }   // .paper 的 padding-bottom

        val h = ops.sumOf { it.h.toDouble() }.toInt().coerceAtLeast(600)
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(PAPER)

        var y = 0f
        for (op in ops) {
            op.draw(cv, y)
            y += op.h
        }
        return bmp
    }

    // ── 区块 ────────────────────────────────────────────────────────────

    /** 报头：深色通栏，报名 + 英文副名 + 期号。 */
    private fun mastheadOp(
        paper: PaperFacts, pMast: Paint, pSub: Paint, pIssue: Paint,
    ): Op {
        val h = 26f + LH_MAST + 8f + LH_SUB + 14f + 2f + 14f + LH_SUB + 22f
        return Op(h) { cv, y ->
            cv.drawRect(0f, y, W.toFloat(), y + h, Paint().apply { color = INK })
            drawTrackedCentered(cv, "鹈鹕镇新闻", pMast, y + 26f, LH_MAST, TRACKING)
            drawCenteredBox(cv, "PELICAN TOWN GAZETTE", pSub, y + 26f + LH_MAST + 8f, LH_SUB)
            val lineY = y + 26f + LH_MAST + 8f + LH_SUB + 14f
            cv.drawRect(
                PAPER_PAD, lineY, W - PAPER_PAD, lineY + 2f,
                Paint().apply { color = MAST_RULE },
            )
            drawCenteredBox(cv, paper.issueLine, pIssue, lineY + 2f + 14f, LH_SUB)
        }
    }

    /** 通栏双细线。 */
    private fun rule2Op(): Op = Op(4f) { cv, y ->
        cv.drawRect(0f, y, W.toFloat(), y + 1f, Paint().apply { color = INK })
        cv.drawRect(0f, y + 3f, W.toFloat(), y + 4f, Paint().apply { color = INK })
    }

    /** 眉题：「Amy 的农场 · 河流农场」。 */
    private fun kickerOp(paper: PaperFacts, p: Paint): Op =
        Op(30f + LH_SUB) { cv, y ->
            drawCenteredBox(cv, paper.kicker, p, y + 30f, LH_SUB)
        }

    /** 头条大字：农场名。 */
    private fun headlineOp(paper: PaperFacts, p: Paint): Op =
        Op(8f + LH_HEAD) { cv, y ->
            drawCenteredBox(cv, paper.headline, p, y + 8f, LH_HEAD)
        }

    /** 副题：称号。 */
    private fun dekOp(paper: PaperFacts, p: Paint): Op =
        Op(12f + LH_SUB) { cv, y ->
            drawCenteredBox(cv, paper.dek, p, y + 12f, LH_SUB)
        }

    /** 头条下的细线。 */
    private fun rule3Op(): Op = Op(26f + 1f) { cv, y ->
        cv.drawRect(
            CONTENT_L, y + 26f, CONTENT_R, y + 27f,
            Paint().apply { color = RULE },
        )
    }

    /** 导语：首行缩进两字。 */
    private fun ledeOp(paper: PaperFacts, p: Paint): Op {
        val indent = FS_BODY * 2f
        val lines = wrapIndent(paper.lede, p, CONTENT_W - indent, CONTENT_W)
        return Op(24f + lines.size * LH_BODY) { cv, y ->
            var ly = y + 24f
            lines.forEachIndexed { i, line ->
                val lx = if (i == 0) CONTENT_L + indent else CONTENT_L
                cv.drawText(line, lx, baselineOf(p, ly, LH_BODY), p)
                ly += LH_BODY
            }
        }
    }

    /**
     * 两栏：左「本期数字」，右「本报评的每日产量」。
     *
     * 两栏各自算高，取大的——**报纸的栏底本来就不齐**（CSS grid 也不齐）。
     */
    private fun numbersColsOp(
        paper: PaperFacts, pColH: Paint, pKey: Paint, pVal: Paint, pBody: Paint,
    ): Op {
        // 值的字号：放不下就降一档（长值如「100 小时 25 分」会挤）
        val rows = paper.numbers.map { n ->
            val kw = pKey.measureText(n.key)
            var size = if (n.big) FS_VAL_BIG else FS_VAL
            while (size > FS_KEY && kw + measureAt(pVal, n.value, size) > COL_W - 8f) {
                size -= 6f
            }
            Triple(n, size, kw)
        }
        val leftH = COLH_H + rows.size * STAT_H
        val perMax = paper.perDay.maxOfOrNull { it.per } ?: 0.0
        val rightH = COLH_H + paper.perDay.size * PER_H
        val colH = max(leftH, rightH)

        return Op(24f + colH) { cv, y ->
            val top = y + 24f
            val lx = CONTENT_L
            val rx = CONTENT_L + COL_W + COL_GAP

            // ── 左栏：本期数字 ──
            drawColHead(cv, "本期数字", lx, top, pColH)
            var ry = top + COLH_H
            for ((n, size, _) in rows) {
                val p = Paint(pVal).apply { textSize = size }
                val base = baselineOf(pKey, ry + 10f, LH_VAL)
                cv.drawText(n.key, lx, base, pKey)
                val vw = p.measureText(n.value)
                cv.drawText(n.value, lx + COL_W - vw, base, p)
                dashedLine(cv, lx, ry + STAT_H - 1f, lx + COL_W)
                ry += STAT_H
            }

            // ── 右栏：每日产量 ──
            drawColHead(cv, "本报评的每日产量", rx, top, pColH)
            ry = top + COLH_H
            for (item in paper.perDay) {
                val base = baselineOf(pKey, ry + 10f, LH_VAL)
                cv.drawText(item.label, rx, base, pKey)
                val vw = pVal.measureText(item.value)
                cv.drawText(item.value, rx + COL_W - vw, base, pVal)
                // 柱状条：10 格，按当日量 / 最大量点亮
                val on = if (perMax > 0) {
                    max(1, min(BAR_CELLS, Math.round(item.per / perMax * BAR_CELLS).toInt()))
                } else {
                    0
                }
                val barY = ry + 10f + LH_VAL + 6f + 8f
                for (i in 0 until BAR_CELLS) {
                    val bx = rx + i * (BAR_W + BAR_GAP)
                    cv.drawRect(bx, barY, bx + BAR_W, barY + BAR_H, Paint().apply {
                        color = if (i < on) RED else TRACK
                    })
                }
                dashedLine(cv, rx, ry + PER_H - 1f, rx + COL_W)
                ry += PER_H
            }
        }
    }

    /** 编者按 / 彩蛋：浅底块 + 左侧朱红竖条。 */
    private fun editorOp(title: String, text: String, pColH: Paint, pBody: Paint): Op {
        val inner = CONTENT_W - EDIT_BAR - EDIT_SIDE * 2
        val lines = wrap(text, pBody, inner)
        val h = 24f + EDIT_PAD + LH_COLH + 10f + lines.size * LH_BODY + EDIT_PAD
        return Op(h) { cv, y ->
            val top = y + 24f
            val blockH = h - 24f
            cv.drawRect(
                CONTENT_L, top, CONTENT_R, top + blockH,
                Paint().apply { color = EDIT_BG },
            )
            cv.drawRect(
                CONTENT_L, top, CONTENT_L + EDIT_BAR, top + blockH,
                Paint().apply { color = RED },
            )
            val tx = CONTENT_L + EDIT_BAR + EDIT_SIDE
            cv.drawText(title, tx, baselineOf(pColH, top + EDIT_PAD, LH_COLH), pColH)
            var ly = top + EDIT_PAD + LH_COLH + 10f
            for (line in lines) {
                cv.drawText(line, tx, baselineOf(pBody, ly, LH_BODY), pBody)
                ly += LH_BODY
            }
        }
    }

    /** 作息时间表：24 格 + 刻度 + 一句话。 */
    private fun clockOp(
        paper: PaperFacts, bt: Bedtime, pColH: Paint, pTick: Paint, pBody: Paint, pBold: Paint,
    ): Op {
        val h = 24f + COLH_H + 14f + CLOCK_H + 8f + LH_SUB + 10f + LH_LIST
        return Op(h) { cv, y ->
            val top = y + 24f
            drawColHead(cv, "作息时间表", CONTENT_L, top, pColH)
            val clockY = top + COLH_H + 14f
            // ⚠ 作息段是**通栏**（预览稿里那一节的 grid 是 1fr 单列），
            //   不是两栏宽——画成 428 会缩在左半边，和预览稿对不上。
            val cw = (CONTENT_W - CLOCK_GAP * 23f) / 24f
            for (h24 in 0 until 24) {
                val x = CONTENT_L + h24 * (cw + CLOCK_GAP)
                val on = h24 == bt.hour
                cv.drawRect(x, clockY, x + cw, clockY + CLOCK_H, Paint().apply {
                    color = if (on) RED else TRACK
                })
                cv.drawRect(x, clockY, x + cw, clockY + CLOCK_H, Paint().apply {
                    color = if (on) INK else RULE
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                })
            }
            // 刻度：四等分 + 末列右对齐（24 等分会把「12」「18」压成数字串）
            val tickY = clockY + CLOCK_H + 8f
            val step = CONTENT_W / 4f
            listOf("0", "6", "12", "18").forEachIndexed { i, t ->
                cv.drawText(t, CONTENT_L + i * step, baselineOf(pTick, tickY, LH_SUB), pTick)
            }
            val last = "24"
            cv.drawText(
                last, CONTENT_R - pTick.measureText(last),
                baselineOf(pTick, tickY, LH_SUB), pTick,
            )
            // 「平均 23:50 上床。」——时间加粗
            val noteY = tickY + LH_SUB + 10f
            val prefix = "平均 "
            cv.drawText(prefix, CONTENT_L, baselineOf(pBody, noteY, LH_LIST), pBody)
            val px = CONTENT_L + pBody.measureText(prefix)
            cv.drawText(bt.text, px, baselineOf(pBold, noteY, LH_LIST), pBold)
            val sx = px + pBold.measureText(bt.text)
            cv.drawText(" 上床。", sx, baselineOf(pBody, noteY, LH_LIST), pBody)
        }
    }

    /** 两栏：手气最重的怪 / 关系最铁的人。 */
    private fun friendsColsOp(
        paper: PaperFacts, pColH: Paint, pBody: Paint, pBold: Paint,
    ): Op {
        if (paper.monsters.isEmpty() && paper.friendsLine.isBlank() && paper.friendNote.isBlank()) {
            return Op(0f) { _, _ -> }
        }
        val leftLines = paper.monsters.size
        val rightLines = (if (paper.friendsLine.isNotBlank()) 1 else 0) +
            (if (paper.friendNote.isNotBlank()) 1 else 0)
        val bodyH = max(leftLines, rightLines) * LH_LIST
        return Op(24f + COLH_H + bodyH) { cv, y ->
            val top = y + 24f
            val lx = CONTENT_L
            val rx = CONTENT_L + COL_W + COL_GAP
            drawColHead(cv, "手气最重的怪", lx, top, pColH)
            drawColHead(cv, "关系最铁的人", rx, top, pColH)
            var ly = top + COLH_H
            for ((name, count) in paper.monsters) {
                val base = baselineOf(pBody, ly, LH_LIST)
                cv.drawText(name, lx, base, pBody)
                val nx = lx + pBody.measureText(name) + 8f
                cv.drawText(count, nx, base, pBold)
                ly += LH_LIST
            }
            ly = top + COLH_H
            if (paper.friendsLine.isNotBlank()) {
                cv.drawText(paper.friendsLine, rx, baselineOf(pBody, ly, LH_LIST), pBody)
                ly += LH_LIST
            }
            if (paper.friendNote.isNotBlank()) {
                cv.drawText(paper.friendNote, rx, baselineOf(pBody, ly, LH_LIST), pBody)
            }
        }
    }

    /** 底栏：上边框 + 左水印右日期。 */
    private fun footOp(paper: PaperFacts, p: Paint): Op =
        Op(30f + 2f + 14f + LH_FOOT) { cv, y ->
            cv.drawRect(
                CONTENT_L, y + 30f, CONTENT_R, y + 32f,
                Paint().apply { color = INK },
            )
            val base = baselineOf(p, y + 30f + 2f + 14f, LH_FOOT)
            cv.drawText(paper.footLeft, CONTENT_L, base, p)
            val rw = p.measureText(paper.footRight)
            cv.drawText(paper.footRight, CONTENT_R - rw, base, p)
        }

    // ── 小工具 ──────────────────────────────────────────────────────────

    /** 栏标题：朱红粗体 + 下方墨线。 */
    private fun drawColHead(cv: Canvas, title: String, x: Float, top: Float, p: Paint) {
        cv.drawText(title, x, baselineOf(p, top, LH_COLH), p)
        val lineY = top + LH_COLH + 8f
        cv.drawRect(x, lineY, x + COL_W, lineY + 2f, Paint().apply { color = INK })
    }

    /** 表格虚线（`.stat` 的 border-bottom: 1px dotted）。 */
    private fun dashedLine(cv: Canvas, x1: Float, y: Float, x2: Float) {
        val p = Paint().apply {
            color = DOTTED
            style = Paint.Style.STROKE
            strokeWidth = 1f
            pathEffect = DashPathEffect(floatArrayOf(2f, 4f), 0f)
        }
        cv.drawLine(x1, y, x2, y, p)
    }

    /**
     * 行盒里的文字基线。
     *
     * 浏览器把 line-height 多出来的空间平分到上下（half-leading），
     * Canvas 没有这回事，所以要自己算——**这是两处渲染对不齐的常见原因**。
     */
    private fun baselineOf(p: Paint, boxTop: Float, boxH: Float): Float {
        val fm = p.fontMetrics
        return boxTop + (boxH - (fm.descent - fm.ascent)) / 2f - fm.ascent
    }

    /** 居中画一行字（按给定行盒垂直居中）。 */
    private fun drawCenteredBox(cv: Canvas, text: String, p: Paint, boxTop: Float, boxH: Float) {
        if (text.isEmpty()) return
        cv.drawText(text, (W - p.measureText(text)) / 2f, baselineOf(p, boxTop, boxH), p)
    }

    /** 居中画一行**带字距**的字（报头 `letter-spacing: 2px`）。 */
    private fun drawTrackedCentered(
        cv: Canvas, text: String, p: Paint, boxTop: Float, boxH: Float, tracking: Float,
    ) {
        if (text.isEmpty()) return
        var total = 0f
        for (ch in text) total += p.measureText(ch.toString())
        total += tracking * (text.length - 1)
        var x = (W - total) / 2f
        val base = baselineOf(p, boxTop, boxH)
        for (ch in text) {
            cv.drawText(ch.toString(), x, base, p)
            x += p.measureText(ch.toString()) + tracking
        }
    }

    /** 用指定字号量一次文字宽度（不改动原 Paint）。 */
    private fun measureAt(p: Paint, text: String, size: Float): Float {
        val tmp = Paint(p).apply { textSize = size }
        return tmp.measureText(text)
    }

    /**
     * 按宽度折行。
     *
     * 中文按字断；遇到英文单词尽量在空格处断（避免把 "Pelican" 劈成两半）。
     */
    private fun wrap(text: String, paint: Paint, maxW: Float): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var line = ""
        for (ch in text) {
            val trial = line + ch
            if (paint.measureText(trial) <= maxW || line.isEmpty()) {
                line = trial
                continue
            }
            val cut = line.lastIndexOf(' ')
            if (cut > 0 && line.length - cut <= 12) {
                out.add(line.substring(0, cut))
                line = line.substring(cut + 1) + ch
            } else {
                out.add(line)
                line = ch.toString()
            }
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    /** 折行，但首行留出缩进（报纸导语 `text-indent: 2em`）。 */
    private fun wrapIndent(text: String, paint: Paint, firstW: Float, restW: Float): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var line = ""
        var limit = firstW
        for (ch in text) {
            val trial = line + ch
            if (paint.measureText(trial) <= limit || line.isEmpty()) {
                line = trial
                continue
            }
            val cut = line.lastIndexOf(' ')
            if (cut > 0 && line.length - cut <= 12) {
                out.add(line.substring(0, cut))
                line = line.substring(cut + 1) + ch
            } else {
                out.add(line)
                line = ch.toString()
            }
            limit = restW
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    /** 一个绘制项：自己知道多高，画的时候拿到底部坐标。 */
    private class Op(val h: Float, val draw: (Canvas, Float) -> Unit)
}

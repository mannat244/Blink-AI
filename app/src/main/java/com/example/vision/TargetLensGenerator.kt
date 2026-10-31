package com.example.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

data class OpticalTargetCard(
    val id: String,
    val shortTitle: String,
    val categoryBadge: String,
    val headerLine: String,
    val bodyLines: List<String>,
    val footerQuestion: String,
    val accentColorHex: Int
)

/**
 * Generates crisp, realistic visual problem targets (laptop code screen, calculus problem,
 * system architecture slide, algorithm quiz) so users on both physical cameras and cloud
 * streaming emulators can test automatic optical lock-on and multi-image context chaining.
 */
object TargetLensGenerator {

    val targets: List<OpticalTargetCard> = listOf(
        OpticalTargetCard(
            id = "math_integral",
            shortTitle = "Calculus & Linear Algebra",
            categoryBadge = "MATH • PART 1",
            headerLine = "Problem 4.2 — Definite Integral & Eigenvalues",
            bodyLines = listOf(
                "1) Evaluate the definite integral:",
                "   I = ∫ from 0 to π/2 of (sin³(x) · cos(x)) dx",
                "",
                "2) Let matrix A = [ [4, 1], [2, 3] ]",
                "   Find both eigenvalues λ₁, λ₂ and trace(A)."
            ),
            footerQuestion = "Find the exact numeric value of I and eigenvalues λ₁, λ₂.",
            accentColorHex = 0xFF00E5FF.toInt()
        ),
        OpticalTargetCard(
            id = "code_bug",
            shortTitle = "Python Async Race Bug",
            categoryBadge = "CODE • IDE SCREEN",
            headerLine = "payment_worker.py — Unhandled Concurrency Bug",
            bodyLines = listOf(
                "async def transfer(account_db, user_id, amount):",
                "    balance = await account_db.get_balance(user_id)",
                "    if balance >= amount:",
                "        # Bug: concurrent coroutines pass check simultaneously",
                "        await asyncio.sleep(0.05)",
                "        await account_db.set_balance(user_id, balance - amount)",
                "        return True",
                "    return False"
            ),
            footerQuestion = "Why does double-spending occur here and how do we fix it safely?",
            accentColorHex = 0xFF00F5D4.toInt()
        ),
        OpticalTargetCard(
            id = "mcq_algo",
            shortTitle = "Algorithm Complexity MCQ",
            categoryBadge = "CS EXAM • QUESTION 12",
            headerLine = "Graph Shortest Path Complexity",
            bodyLines = listOf(
                "Which algorithm finds single-source shortest paths in a",
                "weighted directed graph with NEGATIVE edge weights",
                "(and no negative cycles) in O(V · E) time?",
                "",
                "  (A) Dijkstra's Algorithm with Binary Heap",
                "  (B) Bellman-Ford Algorithm",
                "  (C) Prim's Minimum Spanning Tree",
                "  (D) Kruskal's Union-Find Algorithm"
            ),
            footerQuestion = "Select the correct option (A, B, C, or D).",
            accentColorHex = 0xFFFFC400.toInt()
        ),
        OpticalTargetCard(
            id = "arch_slide",
            shortTitle = "Distributed Cache Slide",
            categoryBadge = "SLIDE • DECK P.2",
            headerLine = "Q3 Production Latency & Cache Hit Telemetry",
            bodyLines = listOf(
                "• Edge CDN Hit Ratio: 94.2% (p95 latency: 11 ms)",
                "• Redis Cluster Hit Ratio: 61.5% (Eviction spike at 14:00 UTC)",
                "• Primary Postgres Read Replica CPU: 96% utilization",
                "• Root Cause Note: Hot-key stampede on /api/v2/feed",
                "  when 60s TTL expires simultaneously across 40k clients."
            ),
            footerQuestion = "Analyze this slide and recommend 3 concrete mitigations.",
            accentColorHex = 0xFF7C4DFF.toInt()
        )
    )

    fun renderTargetToBitmap(target: OpticalTargetCard, width: Int = 1080, height: Int = 810): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.parseColor("#0A101D"),
                Color.parseColor("#131E32"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Draw subtle screen bezel / card frame
        val cardRect = RectF(56f, 56f, width - 56f, height - 56f)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#101827")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(cardRect, 28f, 28f, cardPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = target.accentColorHex
            style = Paint.Style.STROKE
            strokeWidth = 4f
            alpha = 180
        }
        canvas.drawRoundRect(cardRect, 28f, 28f, borderPaint)

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = target.accentColorHex
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText(target.categoryBadge, 96f, 122f, badgePaint)

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(target.headerLine, 96f, 178f, headerPaint)

        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#26354D")
            strokeWidth = 2f
        }
        canvas.drawLine(96f, 206f, width - 96f, 206f, dividerPaint)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2ECF8")
            textSize = 29f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }

        var y = 264f
        for (line in target.bodyLines) {
            canvas.drawText(line, 96f, y, bodyPaint)
            y += 46f
        }

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = target.accentColorHex
            textSize = 28f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(target.footerQuestion, 96f, height - 96f, footerPaint)

        return bmp
    }
}

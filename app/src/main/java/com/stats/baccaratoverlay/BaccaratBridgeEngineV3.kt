package com.stats.baccaratoverlay

import java.util.Locale
import kotlin.math.max

data class V3BridgeReport(
    val sideMainText: String,       // Hiển thị ngay kế bên con Robot: VD "🔵 CON 64.3%"
    val sideSubText: String,        // Tỷ lệ thật của bàn: "Thật: P 10 (48%) | B 9 (43%) | T 2"
    val sideBridgeTag: String,      // Tên cầu thật đang bắt được: "Cầu 1-1 • Ăn: 6W-2L (75%)"
    val recommendedSide: String,    // "P", "B", or "WAIT"
    val lastEventText: String,      // "Vừa bắt: 🟢 HÒA (TIE)"
    val bridgeDetailText: String,   // Giải thích chi tiết xác suất thực tế
    val winLossText: String,        // "Hiệu suất chốt thật: 6W - 2L (75.0%)"
    val historyTapeText: String     // Chuỗi 14 tay gần nhất
)

object BaccaratBridgeEngineV3 {

    // Ngưỡng xác suất thực tế sau hiệu chỉnh Bayes để xuất lệnh Con/Cái
    private const val BASE_CONFIDENCE_THRESHOLD = 56.5

    fun analyze(
        rawHistory: List<String>,
        toolWins: Int,
        toolLosses: Int,
        lastSourceNote: String
    ): V3BridgeReport {
        val total = rawHistory.size
        val winTotal = toolWins + toolLosses
        val toolAccStr = if (winTotal > 0) {
            val acc = (toolWins.toDouble() / winTotal) * 100.0
            String.format(Locale.US, "%dW-%dL (%.0f%%)", toolWins, toolLosses, acc)
        } else {
            "Chưa chốt"
        }

        val winLossFull = if (winTotal > 0) {
            val acc = (toolWins.toDouble() / winTotal) * 100.0
            String.format(Locale.US, "Hiệu suất chốt thật: %dW - %dL (%.1f%%)", toolWins, toolLosses, acc)
        } else {
            "Hiệu suất chốt thật: 0W - 0L (Đang thu thập mẫu)"
        }

        if (total == 0) {
            return V3BridgeReport(
                sideMainText = "👁️ AUTO SOI BÀN...",
                sideSubText = "Thật: P 0 (0%) | B 0 (0%) | T 0",
                sideBridgeTag = "Toàn màn hình • Tự bắt Con/Cái/Hòa",
                recommendedSide = "WAIT",
                lastEventText = "Vừa bắt: Chờ ván đầu tiên...",
                bridgeDetailText = "Phân tích cầu thật: Tự động đọc khi bàn ra kết quả hoặc bấm Quét Bảng Cầu",
                winLossText = winLossFull,
                historyTapeText = "Chuỗi tay bắt được: Trống"
            )
        }

        val pCount = rawHistory.count { it == "P" }
        val bCount = rawHistory.count { it == "B" }
        val tCount = rawHistory.count { it == "T" }

        val pPctReal = (pCount.toDouble() / total) * 100.0
        val bPctReal = (bCount.toDouble() / total) * 100.0

        val sideSub = String.format(
            Locale.US,
            "Thật: P %d (%.0f%%) | B %d (%.0f%%) | T %d",
            pCount, pPctReal, bCount, bPctReal, tCount
        )

        val lastRaw = rawHistory.last()
        val lastDesc = when (lastRaw) {
            "P" -> "🔵 CON (PLAYER)"
            "B" -> "🔴 CÁI (BANKER)"
            else -> "🟢 HÒA (TIE)"
        }
        val lastEventLine = "Vừa bắt: $lastDesc ($lastSourceNote)"

        val tapeStr = "Chuỗi (${rawHistory.size} ván): ${rawHistory.takeLast(14).joinToString("-")}"

        // Lọc chuỗi P/B (Hòa giữ nguyên nhịp cầu đang chạy, không làm ngắt cầu)
        val pb = rawHistory.filter { it == "P" || it == "B" }
        val pbSize = pb.size

        if (pbSize < 3) {
            return V3BridgeReport(
                sideMainText = "⏳ ĐỌC DATA ($pbSize/3)",
                sideSubText = sideSub,
                sideBridgeTag = "Vừa ra: $lastRaw • Đang lấy nhịp cầu",
                recommendedSide = "WAIT",
                lastEventText = lastEventLine,
                bridgeDetailText = "Phân tích cầu thật: Cần đủ 3 tay P/B thực tế để khóa nhịp cầu",
                winLossText = winLossFull,
                historyTapeText = tapeStr
            )
        }

        // 1. Nhận diện Cầu Bệt (Streak) & Cầu 1-1 (Ping-Pong) từ chuỗi thực tế
        val lastSide = pb.last()
        var currentStreak = 0
        for (i in pb.indices.reversed()) {
            if (pb[i] == lastSide) currentStreak++ else break
        }

        var alternatingRun = 1
        for (i in pb.size - 1 downTo 1) {
            if (pb[i] != pb[i - 1]) alternatingRun++ else break
        }

        // 2. Tính xác suất chuyển tiếp thực tế bậc 1, bậc 2 và bậc 3 (hiệu chỉnh Bayes tránh 100% ảo)
        var p1 = 0
        var b1 = 0
        for (i in 0 until pbSize - 1) {
            if (pb[i] == lastSide) {
                if (pb[i + 1] == "P") p1++ else b1++
            }
        }
        val n1 = p1 + b1

        var p2 = 0
        var b2 = 0
        val a1 = pb[pbSize - 2]
        val a2 = pb[pbSize - 1]
        for (i in 0 until pbSize - 2) {
            if (pb[i] == a1 && pb[i + 1] == a2) {
                if (pb[i + 2] == "P") p2++ else b2++
            }
        }
        val n2 = p2 + b2

        var p3 = 0
        var b3 = 0
        if (pbSize >= 4) {
            val t1 = pb[pbSize - 3]
            val t2 = pb[pbSize - 2]
            val t3 = pb[pbSize - 1]
            for (i in 0 until pbSize - 3) {
                if (pb[i] == t1 && pb[i + 1] == t2 && pb[i + 2] == t3) {
                    if (pb[i + 3] == "P") p3++ else b3++
                }
            }
        }
        val n3 = p3 + b3

        // Tính tỷ lệ thực tế có làm mượt Laplace (count + 1) / (n + 2) -> không bao giờ báo 100% ảo
        val chosenSide: String
        val realEdgePct: Double
        val bridgeName: String
        val detailExplanation: String

        if (n3 >= 2 && max(p3, b3).toDouble() / n3 >= 0.65) {
            val side = if (p3 > b3) "P" else "B"
            val hits = max(p3, b3)
            realEdgePct = ((hits + 1.0) / (n3 + 2.0)) * 100.0
            chosenSide = side
            bridgeName = "Cầu Mẫu 3 Tay ($hits/$n3)"
            detailExplanation = String.format(
                Locale.US,
                "Khớp mẫu 3 tay gần nhất %d/%d lần -> Xác suất thật %.1f%%",
                hits, n3, realEdgePct
            )
        } else if (n2 >= 2 && max(p2, b2).toDouble() / n2 >= 0.60) {
            val side = if (p2 > b2) "P" else "B"
            val hits = max(p2, b2)
            realEdgePct = ((hits + 1.0) / (n2 + 2.0)) * 100.0
            chosenSide = side
            bridgeName = if (currentStreak >= 3) "Cầu Bệt Dây $currentStreak"
            else if (alternatingRun >= 4) "Cầu 1-1 ($alternatingRun tay)"
            else "Cầu Nhịp [$a1-$a2] ($hits/$n2)"
            detailExplanation = String.format(
                Locale.US,
                "Nhịp [%s-%s] lặp lại %d/%d lần thực tế -> Tỷ lệ thật %.1f%%",
                a1, a2, hits, n2, realEdgePct
            )
        } else if (currentStreak >= 3) {
            // Cầu bệt đang chạy: kiểm tra tỷ lệ bệt kéo dài trong chính bàn này
            val contHits = if (lastSide == "P") p1 else b1
            realEdgePct = if (n1 >= 2) {
                ((contHits + 1.5) / (n1 + 2.5)) * 100.0
            } else {
                61.5
            }
            chosenSide = lastSide
            bridgeName = "Cầu Bệt Dây $currentStreak"
            detailExplanation = String.format(
                Locale.US,
                "Đang chạy bệt %s dây %d -> Theo bệt tỷ lệ thật %.1f%%",
                if (lastSide == "P") "CON" else "CÁI", currentStreak, realEdgePct
            )
        } else if (alternatingRun >= 4) {
            // Cầu 1-1 đang chạy ổn định >= 4 nhịp
            val opposite = if (lastSide == "P") "B" else "P"
            val chopHits = if (opposite == "P") p1 else b1
            realEdgePct = if (n1 >= 2) {
                ((chopHits + 1.5) / (n1 + 2.5)) * 100.0
            } else {
                60.0
            }
            chosenSide = opposite
            bridgeName = "Cầu 1-1 ($alternatingRun nhịp)"
            detailExplanation = String.format(
                Locale.US,
                "Cầu 1-1 chạy %d nhịp liên tiếp -> Đảo chiều tỷ lệ thật %.1f%%",
                alternatingRun, realEdgePct
            )
        } else if (n1 >= 3) {
            val side = if (p1 > b1) "P" else "B"
            val hits = max(p1, b1)
            realEdgePct = ((hits + 1.0) / (n1 + 2.0)) * 100.0
            chosenSide = side
            bridgeName = "Cầu Chuyển [$lastSide] ($hits/$n1)"
            detailExplanation = String.format(
                Locale.US,
                "Sau [%s] ra %s %d/%d lần -> Xác suất thật %.1f%%",
                lastSide, if (side == "P") "CON" else "CÁI", hits, n1, realEdgePct
            )
        } else {
            // Chưa đủ mẫu lặp rõ ràng -> so sánh độ lệch thực của cả bàn
            val pbTotal = pCount + bCount
            val side = if (pCount >= bCount) "P" else "B"
            val hits = max(pCount, bCount)
            realEdgePct = ((hits + 1.0) / (pbTotal + 2.0)) * 100.0
            chosenSide = side
            bridgeName = "Nghiêng Bàn ($hits/$pbTotal)"
            detailExplanation = String.format(
                Locale.US,
                "Bàn đang nghiêng %s (%d/%d tay) -> Tỷ lệ thật %.1f%%",
                if (side == "P") "CON" else "CÁI", hits, pbTotal, realEdgePct
            )
        }

        // Nếu xác suất thật chưa vượt ngưỡng lọc (cầu 50/50 nhiễu), báo CHỜ CẦU để bảo toàn tỷ lệ ăn
        if (realEdgePct < BASE_CONFIDENCE_THRESHOLD) {
            val pctStr = String.format(Locale.US, "%.1f%%", realEdgePct)
            return V3BridgeReport(
                sideMainText = "⏸ CHỜ CẦU ($pctStr)",
                sideSubText = sideSub,
                sideBridgeTag = "Cầu 50/50 nhiễu • Ăn: $toolAccStr",
                recommendedSide = "WAIT",
                lastEventText = lastEventLine,
                bridgeDetailText = "Bỏ qua tay 50/50 ($detailExplanation)",
                winLossText = winLossFull,
                historyTapeText = tapeStr
            )
        }

        val pctFormatted = String.format(Locale.US, "%.1f%%", realEdgePct)
        return if (chosenSide == "P") {
            V3BridgeReport(
                sideMainText = "🔵 CON $pctFormatted",
                sideSubText = sideSub,
                sideBridgeTag = "$bridgeName • Ăn: $toolAccStr",
                recommendedSide = "P",
                lastEventText = lastEventLine,
                bridgeDetailText = detailExplanation,
                winLossText = winLossFull,
                historyTapeText = tapeStr
            )
        } else {
            V3BridgeReport(
                sideMainText = "🔴 CÁI $pctFormatted",
                sideSubText = sideSub,
                sideBridgeTag = "$bridgeName • Ăn: $toolAccStr",
                recommendedSide = "B",
                lastEventText = lastEventLine,
                bridgeDetailText = detailExplanation,
                winLossText = winLossFull,
                historyTapeText = tapeStr
            )
        }
    }
}

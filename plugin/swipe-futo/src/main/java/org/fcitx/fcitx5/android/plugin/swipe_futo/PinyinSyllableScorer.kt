/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.swipe_futo

internal object PinyinSyllableScorer {

    fun score(candidate: String): Float {
        val normalized = normalizeCandidate(candidate)
        if (normalized.isEmpty()) return 0f

        val best = bestSegmentation(normalized)
        val coverageScore = best.coveredChars.toFloat() / normalized.length.toFloat()
        if (best.syllables == 0) return coverageScore * 0.72f

        val averageSyllableLength = best.coveredChars.toFloat() / best.syllables.toFloat()
        val compactnessScore = (averageSyllableLength / 3f).coerceIn(0f, 1f)
        val strongSyllableScore = 1f - best.weakSyllables.toFloat() / best.syllables.toFloat()

        return coverageScore * 0.72f +
            compactnessScore * 0.18f +
            strongSyllableScore * 0.10f
    }

    fun isStrongTraceCandidate(candidate: String): Boolean {
        val normalized = normalizeCandidate(candidate)
        return normalized.length >= MIN_TRACE_CANDIDATE_LENGTH &&
            score(normalized) >= STRONG_TRACE_CANDIDATE_THRESHOLD
    }

    fun swipeRepairCandidates(candidate: String): List<String> {
        return swipeRepairDepths(candidate).keys.toList()
    }

    /**
     * Returns every strong repair together with the number of trace-repair rules it used.
     * A multi-rule repair represents multiple skipped key transitions in one continuous swipe;
     * it is not a generic spelling-edit distance.
     */
    fun swipeRepairDepths(candidate: String): Map<String, Int> {
        val normalized = normalizeCandidate(candidate)
        if (normalized.length !in MIN_TRACE_CANDIDATE_LENGTH..MAX_REPAIR_CANDIDATE_LENGTH) {
            return emptyMap()
        }

        val repairs = linkedMapOf<String, Int>()
        collectSwipeRepairs(
            input = normalized,
            startIndex = 0,
            current = StringBuilder(normalized.length + MAX_REPAIR_DEPTH),
            repairCount = 0,
            output = repairs
        )
        return repairs
    }

    private fun collectSwipeRepairs(
        input: String,
        startIndex: Int,
        current: StringBuilder,
        repairCount: Int,
        output: MutableMap<String, Int>
    ) {
        if (output.size >= MAX_REPAIR_CANDIDATES || repairCount > MAX_REPAIR_DEPTH) {
            return
        }
        if (startIndex >= input.length) {
            current.toString()
                .takeIf { it != input && isStrongTraceCandidate(it) }
                ?.let { repaired ->
                    output.merge(repaired, repairCount, ::minOf)
                }
            return
        }

        REPAIR_RULES.forEach { rule ->
            if (
                repairCount < MAX_REPAIR_DEPTH &&
                input.startsWith(rule.from, startIndex)
            ) {
                val oldLength = current.length
                current.append(rule.to)
                collectSwipeRepairs(
                    input = input,
                    startIndex = startIndex + rule.from.length,
                    current = current,
                    repairCount = repairCount + 1,
                    output = output
                )
                current.setLength(oldLength)
            }
        }

        current.append(input[startIndex])
        collectSwipeRepairs(
            input = input,
            startIndex = startIndex + 1,
            current = current,
            repairCount = repairCount,
            output = output
        )
        current.setLength(current.length - 1)
    }

    private fun bestSegmentation(input: String): SegmentationScore {
        val best = Array(input.length + 1) { SegmentationScore() }
        for (index in input.indices) {
            val current = best[index]
            best[index + 1] = maxOf(best[index + 1], current.skipped(), SegmentationScore::compareTo)
            for (end in index + 1..minOf(input.length, index + MAX_SYLLABLE_LENGTH)) {
                val syllable = input.substring(index, end)
                if (syllable !in VALID_SYLLABLES) continue
                val candidate = current.matched(syllable)
                best[end] = maxOf(best[end], candidate, SegmentationScore::compareTo)
            }
        }
        return best.last()
    }

    private fun normalizeCandidate(candidate: String): String =
        buildString(candidate.length) {
            candidate.forEach { character ->
                val normalized = character.lowercaseChar()
                if (normalized in 'a'..'z') append(normalized)
            }
        }

    private data class SegmentationScore(
        val coveredChars: Int = 0,
        val weakSyllables: Int = 0,
        val syllables: Int = 0,
        val skippedChars: Int = 0
    ) : Comparable<SegmentationScore> {
        fun matched(syllable: String) = copy(
            coveredChars = coveredChars + syllable.length,
            weakSyllables = weakSyllables + if (syllable in WEAK_STANDALONE_SYLLABLES) 1 else 0,
            syllables = syllables + 1
        )

        fun skipped() = copy(skippedChars = skippedChars + 1)

        override fun compareTo(other: SegmentationScore): Int =
            compareValuesBy(
                this,
                other,
                { it.coveredChars },
                { -it.weakSyllables },
                { -it.syllables },
                { -it.skippedChars }
            )
    }

    private const val MAX_SYLLABLE_LENGTH = 6
    private const val MIN_TRACE_CANDIDATE_LENGTH = 2
    private const val STRONG_TRACE_CANDIDATE_THRESHOLD = 0.93f
    private const val MAX_REPAIR_CANDIDATE_LENGTH = 24
    private const val MAX_REPAIR_CANDIDATES = 16
    private const val MAX_REPAIR_DEPTH = 3

    private val WEAK_STANDALONE_SYLLABLES = setOf("a", "e", "o")
    private data class RepairRule(val from: String, val to: String)

    private val REPAIR_RULES = listOf(
        RepairRule("fuo", "fou"),
        RepairRule("fo", "fou"),
        RepairRule("zong", "zhong"),
        RepairRule("zou", "zhou"),
        RepairRule("zuo", "zhuo"),
        RepairRule("zui", "zhui"),
        RepairRule("zun", "zhun"),
        RepairRule("cong", "chong"),
        RepairRule("cou", "chou"),
        RepairRule("cuo", "chuo"),
        RepairRule("cui", "chui"),
        RepairRule("cun", "chun"),
        RepairRule("sou", "shou"),
        RepairRule("suo", "shuo"),
        RepairRule("sui", "shui"),
        RepairRule("sun", "shun"),
        RepairRule("si", "shi"),
        RepairRule("ci", "chi"),
        RepairRule("zi", "zhi")
    )

    private val VALID_SYLLABLES = """
        a ai an ang ao e ei en eng er o ou
        ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
        wa wai wan wang wei wen weng wo wu
        ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
        pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
        ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
        fa fan fang fei fen feng fo fou fu
        da dai dan dang dao de dei deng di dian diao die ding diu dong dou du duan dui dun duo
        ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
        na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nuo nv nve
        la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve
        ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
        ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo
        ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
        ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun
        qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
        xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
        zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo
        cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo
        sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo
        ran rang rao re ren reng ri rong rou ru ruan rui run ruo
        za zai zan zang zao ze zei zen zeng zi zong zou zu zuan zui zun zuo
        ca cai can cang cao ce cen ceng ci cong cou cu cuan cui cun cuo
        sa sai san sang sao se sen seng si song sou su suan sui sun suo
    """.trimIndent()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .toSet()
}

package com.rubion.nexplaybe.anticipation

import java.text.Normalizer
import org.springframework.stereotype.Component

/**
 * 욕설·비속어 필터.
 *
 * **정규식만으로는 못 막는다.** 한국어 욕설은 우회가 쉽다.
 *
 *   시발 / 시1발 / 시*발 / 시 발 / 시이이발 / ㅅㅣㅂㅏㄹ / ㅆㅂ
 *
 * 금칙어를 그대로 찾는 대신 **글자를 자모까지 풀어 우회를 되돌린 뒤**
 * 대조한다. 순서가 중요하다.
 *
 *   1. 유니코드 정규화(NFKC) — 전각·호환 문자를 표준형으로
 *   2. 공백·기호·숫자 제거 — "시 발", "시*발", "시1발"
 *   3. 자모 분해 — "시발"과 "ㅅㅣㅂㅏㄹ"을 같은 자리에서 비교
 *   4. 채움 'ㅇ' 제거 + 반복 축약 — "시이이발", "씨이발"
 *
 * 완벽하지 않다. 마음먹고 우회하면 결국 뚫린다. 이 필터의 현실적인 목표는
 * "실수로 튀어나오는 것"과 "성의 없는 우회"까지고, 그 뒤는 신고 기능이 받는다.
 *
 * **오탐이 미탐보다 나쁘다.** 정상 글이 막히면 사용자는 이유도 모른 채
 * 쫓겨난다. 그래서 초성 대조는 흔한 조합을 쓰지 않는다 — "ㅂㅅ"(병신)은
 * "부산·분석·배송"과 같고, "ㄷㅊ"(닥쳐)은 "반도체"에 그대로 들어 있다.
 */
@Component
class ProfanityFilter {

    /** 걸리면 등록을 막는다. 어디가 걸렸는지는 응답에 넣지 않는다 — 우회를 가르쳐 주는 셈이다. */
    fun isClean(text: String): Boolean = findFirst(text) == null

    /** 걸린 금칙어. 로그와 테스트용이다. */
    fun findFirst(text: String): String? {
        if (text.isBlank()) return null

        val kept = keepMeaningful(text)
        if (kept.isEmpty()) return null

        // 자모로 풀어 비교한다. "시발"과 "ㅅㅣㅂㅏㄹ" 이 같은 문자열이 된다.
        val plain = decompose(kept, dropFillerIeung = false)
        // 채움 'ㅇ' 을 뺀 형태도 함께 본다. "시이이발" → "시발".
        val squeezed = decompose(kept, dropFillerIeung = true)

        WORDS.firstOrNull { word ->
            val pattern = JAMO_WORDS.getValue(word)
            plain.containsAligned(pattern) || squeezed.containsAligned(pattern)
        }?.let { return it }

        val initials = initialsOf(kept)
        return INITIALS.firstOrNull { initials.contains(it) }
    }

    /**
     * 자모로 푼 문자열과, 각 자리가 음절의 첫 자모인지.
     *
     * 경계 정보가 없으면 음절을 가로질러 오탐한다. "조정"은 자모로
     * ㅈㅗ|ㅈㅓㅇ 인데, 그냥 부분 문자열로 찾으면 "좆"(ㅈㅗㅈ)이 들어 있다.
     * 실제로 "느낌상 조정 올 것 같은데"가 이것 때문에 막혔다.
     */
    private class Decomposed(val text: String, private val starts: BooleanArray) {

        /**
         * 음절 경계에서 시작하고 경계에서 끝나는 자리만 매칭으로 본다.
         *
         * 끝 경계까지 보는 게 핵심이다. "좆"은 "조정"의 앞에서 시작하지만
         * 중성 한가운데서 끝나므로 걸러진다. "좆같다"는 '같'의 첫 자모에서
         * 끝나므로 걸린다.
         */
        fun containsAligned(pattern: String): Boolean {
            if (pattern.isEmpty() || pattern.length > text.length) return false

            var from = 0
            while (true) {
                val at = text.indexOf(pattern, from)
                if (at < 0) return false

                val end = at + pattern.length
                if (starts[at] && (end == text.length || starts[end])) return true
                from = at + 1
            }
        }
    }

    /**
     * 자모로 풀면서 음절 경계를 함께 기록한다.
     *
     * 이미 자모로 적힌 글자(ㅅㅣㅂㅏㄹ)는 경계를 알 수 없으므로 모든 자리를
     * 시작점으로 본다. 자모만으로 쓴 정상 단어는 없어서 손해가 없다.
     *
     * @param dropFillerIeung 초성 'ㅇ' 을 뺀다. "시이발"의 '이'처럼 음가 없이
     *   글자 수만 늘리는 채움을 지우려는 것이다. 종성 'ㅇ'(방·상)은 실제
     *   소리라 남긴다 — 빼면 "방송"과 "바소"가 같아져 오탐이 늘어난다.
     */
    private fun decompose(text: String, dropFillerIeung: Boolean): Decomposed {
        val out = StringBuilder(text.length * 3)
        val starts = ArrayList<Boolean>(text.length * 3)

        fun add(ch: Char, isStart: Boolean) {
            // 같은 자모가 이어지면 하나로 줄인다. "ㅅㅣㅣㅣㅂㅏㄹ" → "ㅅㅣㅂㅏㄹ"
            if (out.isNotEmpty() && out.last() == ch) return
            out.append(ch)
            starts.add(isStart)
        }

        for (ch in text) {
            if (ch in SYLLABLE_START..SYLLABLE_END) {
                val offset = ch - SYLLABLE_START
                val initial = INITIAL_JAMO[offset / (21 * 28)]
                val medial = MEDIAL_JAMO[(offset % (21 * 28)) / 28]
                val finalIndex = offset % 28

                if (dropFillerIeung && initial == 'ㅇ') {
                    add(medial, true)
                } else {
                    add(initial, true)
                    add(medial, false)
                }
                if (finalIndex > 0) add(FINAL_JAMO[finalIndex], false)
            } else {
                add(ch, true)
            }
        }

        return Decomposed(out.toString(), starts.toBooleanArray())
    }

    /**
     * 우회 수단이 되는 문자를 걷어낸다.
     *
     * 숫자를 지우는 건 "시1발" 때문이다. 그 탓에 "1억"이 "억"이 되지만
     * 금칙어에 숫자가 들어가는 경우가 없어 문제되지 않는다.
     */
    private fun keepMeaningful(text: String): String {
        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKC)
        return buildString(normalized.length) {
            for (ch in normalized) {
                val letter = ch.toCompatibilityJamo()
                if (letter.isHangul() || letter in 'a'..'z') append(letter)
            }
        }
    }

    /**
     * 조합용 자모를 호환 자모로 되돌린다.
     *
     * NFKC 가 "ㅅ"(U+3145, 호환 자모)을 U+1109(조합용 초성)로 바꿔 버린다.
     * 사전과 초성 배열은 호환 자모로 적혀 있어 그대로 두면 어긋난다.
     * 실제로 "ㅅㅣㅂㅏㄹ" 이 통째로 걸러져 빈 문자열이 되는 바람에 무사
     * 통과하고 있었다.
     */
    private fun Char.toCompatibilityJamo(): Char = when (this) {
        in CHOSEONG_START..CHOSEONG_END -> INITIAL_JAMO[this - CHOSEONG_START]
        in JUNGSEONG_START..JUNGSEONG_END -> MEDIAL_JAMO[this - JUNGSEONG_START]
        in JONGSEONG_START..JONGSEONG_END -> FINAL_JAMO[this - JONGSEONG_START + 1]
        else -> this
    }

    /** 초성만 뽑는다. "시발" → "ㅅㅂ", "ㅅㅂ" → "ㅅㅂ" 이라 같은 자리에서 비교된다. */
    private fun initialsOf(text: String): String = buildString(text.length) {
        for (ch in text) {
            when {
                ch in SYLLABLE_START..SYLLABLE_END ->
                    append(INITIAL_JAMO[(ch - SYLLABLE_START) / (21 * 28)])
                ch in CONSONANTS -> append(ch)
                // 모음과 알파벳은 뺀다. 남기면 초성 사이가 벌어져 매칭이 어긋난다.
            }
        }
    }

    private fun Char.isHangul(): Boolean =
        this in SYLLABLE_START..SYLLABLE_END || this in 'ㄱ'..'ㅣ'

    private companion object {
        const val SYLLABLE_START = '가'
        const val SYLLABLE_END = '힣'

        val INITIAL_JAMO = charArrayOf(
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
        )

        val MEDIAL_JAMO = charArrayOf(
            'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ',
            'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ',
        )

        /** 0번은 종성 없음이라 자리만 채운다. */
        val FINAL_JAMO = charArrayOf(
            ' ', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ',
            'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
        )

        /** 초성 자리에 올 수 있는 낱자. 'ㅏ' 이후는 모음이라 뺀다. */
        val CONSONANTS = 'ㄱ'..'ㅎ'

        /** NFKC 가 만들어내는 조합용 자모 구간. 호환 자모로 되돌리는 데 쓴다. */
        const val CHOSEONG_START = 'ᄀ'
        const val CHOSEONG_END = 'ᄒ'
        const val JUNGSEONG_START = 'ᅡ'
        const val JUNGSEONG_END = 'ᅵ'
        const val JONGSEONG_START = 'ᆨ'
        const val JONGSEONG_END = 'ᇂ'

        val WORDS = listOf(
            "시발", "씨발", "시팔", "씨팔", "쉬발", "십새",
            "좆", "존나", "졸라",
            "병신", "빙신", "븅신",
            "지랄", "니미", "니애미", "느금", "니애비",
            "개새", "새끼", "쌍놈", "쌍년", "썅",
            "미친놈", "미친년", "또라이", "돌아이",
            "닥쳐", "꺼져라",
            "fuck", "shit", "bitch", "asshole",
        )

        /**
         * 금칙어도 같은 방식으로 풀어 둔다. 매번 계산하지 않게 미리 만든다.
         * 여기서만 쓰는 최소 인스턴스라 필터를 새로 만들지 않는다.
         */
        val JAMO_WORDS: Map<String, String> = ProfanityFilter().let { filter ->
            WORDS.associateWith { word ->
                filter.decompose(filter.keepMeaningful(word), dropFillerIeung = false).text
            }
        }

        /**
         * 초성 표기.
         *
         * **짧고 흔한 조합은 넣지 않는다.** "ㅂㅅ"은 부산·분석·배송과 같고,
         * "ㄷㅊ"은 반도체 안에 그대로 들어 있다. 실제로 두 조합을 넣었더니
         * "오늘 반도체 좋네요"가 막혔다.
         *
         * 남긴 것들은 일반 단어에서 잘 나오지 않는 세 글자 이상 조합이다.
         */
        val INITIALS = listOf(
            "ㅅㅂㄹㅁ", "ㅆㅂㄹㅁ",
            "ㄱㅅㄲ", "ㄳㄲ",
            "ㅁㅊㄴ", "ㅈㄹㅎㄴ",
            // "ㄴㄱㅁ"(느금)은 "하나 고민"과 같아 뺐다.
            // "ㄴㅁㅊ"(니미친)은 "내 마음처럼"과 같아 뺐다.
        )
    }
}

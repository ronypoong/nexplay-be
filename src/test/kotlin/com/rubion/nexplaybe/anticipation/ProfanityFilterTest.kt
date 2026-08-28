package com.rubion.nexplaybe.anticipation

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 필터는 두 방향으로 틀릴 수 있고, 둘의 무게가 다르다.
 *
 * - 미탐: 욕설이 통과한다. 신고 기능이 뒤를 받친다.
 * - 오탐: 정상 글이 막힌다. 사용자는 이유도 모른 채 쫓겨난다.
 *
 * **오탐이 더 나쁘다.** 그래서 정상 문장 검증을 우회 검증만큼 촘촘히 둔다.
 */
class ProfanityFilterTest {

    private val filter = ProfanityFilter()

    @Test
    fun `그대로 쓴 욕설을 막는다`() {
        listOf("시발", "씨발", "병신", "지랄", "존나", "개새끼")
            .forEach { assertFalse(filter.isClean(it), "통과하면 안 된다: $it") }
    }

    /** 사람들이 실제로 쓰는 우회 표기들. 이게 이 필터의 존재 이유다. */
    @Test
    fun `우회 표기를 되돌려 막는다`() {
        listOf(
            "시 발",        // 공백
            "시*발",        // 기호
            "시1발",        // 숫자
            "시이이발",      // 반복
            "씨이발",
            "시-발",
            "ㅅㅣㅂㅏㄹ",    // 자모 분해
            "병 신",
            "존 나",
            "개 새 끼",
            "ㅋㅋ시발ㅋㅋ",  // 앞뒤에 다른 글자
        ).forEach { assertFalse(filter.isClean(it), "통과하면 안 된다: $it") }
    }

    @Test
    fun `초성 표기도 막는다`() {
        listOf("ㄱㅅㄲ", "ㅁㅊㄴ", "ㅅㅂㄹㅁ")
            .forEach { assertFalse(filter.isClean(it), "통과하면 안 된다: $it") }
    }

    @Test
    fun `영문 욕설도 막는다`() {
        listOf("fuck", "FUCK", "f u c k", "shit")
            .forEach { assertFalse(filter.isClean(it), "통과하면 안 된다: $it") }
    }

    /**
     * 오탐 검증. 주식 게시판에서 실제로 나올 법한 문장들이다.
     * 여기서 하나라도 막히면 필터가 서비스를 망친다.
     */
    @Test
    fun `정상 문장은 통과시킨다`() {
        listOf(
            "오늘 반도체 좋네요",
            "부산 지역 부동산도 오르나요",
            "분석 감사합니다",
            "배송 지연되는 거랑 관계 있을까요",
            "HBM 수요가 계속 늘 것 같습니다",
            "예측이 잘 맞네요 신기합니다",
            "손절해야 하나 고민되네요",
            "실적 발표 언제인가요",
            "매수 타이밍 어떻게 보세요",
            "삼성전자 목표가 상향됐다는데 사실인가요",
            "1억 벌었습니다",
            "시장 전체가 좋아 보입니다",
            "시가총액 기준으로 보면 어떤가요",
            "발표 자료 링크 있나요",
            "미국 증시 영향이 크네요",
            "새로운 기술이 나왔다고 하던데",
            "개인 투자자 순매수가 늘었습니다",
        ).forEach { assertTrue(filter.isClean(it), "막히면 안 된다: $it") }
    }

    /**
     * "시장", "시가" 는 "시" 로 시작하지만 욕설이 아니다.
     * 숫자·공백 제거가 인접한 단어를 붙여 만들어내는 오탐도 없어야 한다.
     */
    @Test
    fun `단어 경계가 붙어도 오탐하지 않는다`() {
        listOf(
            "이번 주 시장 발표",   // "시장 발" → 붙이면 "시장발"
            "종가 시세 확인",
            "분석 시작합니다",
        ).forEach { assertTrue(filter.isClean(it), "막히면 안 된다: $it") }
    }

    /**
     * 실제 게시판에서 나올 법한 문장을 넓게 던져 오탐을 본다.
     * 초성 사전을 늘릴 때마다 이 목록이 먼저 깨지는지 확인한다.
     */
    @Test
    fun `주식 게시판 표현을 오탐하지 않는다`() {
        listOf(
            "하나 고민되는 게 환율이네요",
            "내 마음처럼 안 가네요",
            "느낌상 조정 올 것 같은데",
            "반등 시점이 언제일까요",
            "고민 끝에 분할매수 했습니다",
            "네이버 거래량 늘었네요",
            "다음 주 실적 시즌입니다",
            "미국 금리 인하 기대감",
            "삼성전기 MLCC 좋아 보여요",
            "지수 흐름이 나쁘지 않네요",
            "물타기 해야 할까요",
            "차트상 지지선 확인됩니다",
            "니케이도 같이 올랐네요",
            "개별 종목보다 지수가 낫습니다",
            "새로 들어온 자금이 많네요",
            "쌍바닥 잡은 것 같은데요",
        ).forEach { assertTrue(filter.isClean(it), "막히면 안 된다: $it") }
    }

    /** 자모를 섞어 쓴 우회도 되돌린다. */
    @Test
    fun `자모 혼용 우회를 막는다`() {
        listOf("ㅅㅣ발", "시ㅂㅏㄹ", "ㅂㅕㅇ신", "병ㅅㅣㄴ")
            .forEach { assertFalse(filter.isClean(it), "통과하면 안 된다: $it") }
    }

    /**
     * 음절 경계 검사가 없으면 자모 매칭이 단어를 가로질러 오탐한다.
     * "조정"은 자모로 ㅈㅗ|ㅈㅓㅇ 이라 "좆"(ㅈㅗㅈ)이 들어 있는 것처럼 보인다.
     */
    @Test
    fun `음절을 가로지르는 매칭은 오탐하지 않는다`() {
        listOf("조정 국면입니다", "조정받는 중", "가조정 상태")
            .forEach { assertTrue(filter.isClean(it), "막히면 안 된다: $it") }

        // 경계가 맞으면 잡아야 한다.
        assertFalse(filter.isClean("좆같네"), "통과하면 안 된다: 좆같네")
    }

    @Test
    fun `빈 문자열은 통과시킨다`() {
        assertTrue(filter.isClean(""))
        assertTrue(filter.isClean("   "))
    }
}

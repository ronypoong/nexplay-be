package com.rubion.nexplaybe.intelligence

import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * 모델이 뉴스 하나를 읽고 채워 주는 구조.
 *
 * 요약을 "생성" 시키지 않는다. 원문에 있는 사실만 뽑게 하고, 없으면 null 을 두게 한다.
 * 없는 정보를 지어내면 아카이브 전체가 못 믿을 것이 된다.
 */
data class EventExtraction(
    @field:JsonPropertyDescription(
        "이벤트 유형. ANNOUNCEMENT 는 다른 어느 것에도 해당하지 않을 때만 쓴다. " +
            "가능한 값: RELEASE_DATE, DELAY, RELEASE, PATCH, MAJOR_UPDATE, DLC, EXPANSION, " +
            "TRAILER, GAMEPLAY, DEMO, BETA, DISCOUNT, PREORDER, ANNOUNCEMENT",
    )
    val eventType: String = "",
    @field:JsonPropertyDescription("판단 확신도: HIGH, MEDIUM, LOW 중 하나")
    val confidence: String = "",
    @field:JsonPropertyDescription("한국어 한 줄 요약. 원문에 있는 사실만 쓴다. 최대 120자")
    val summaryKo: String? = null,
    @field:JsonPropertyDescription("할인율(%). 원문에 명시된 숫자만. 없으면 null")
    val discountPercent: Int? = null,
    @field:JsonPropertyDescription("원문이 언급한 출시일(YYYY-MM-DD). 명시되지 않았으면 null")
    val mentionedReleaseDate: String? = null,
    @field:JsonPropertyDescription("체험판/데모 배포를 알리는 글이면 true")
    val hasDemo: Boolean = false,
    @field:JsonPropertyDescription("게임 내용과 무관한 마케팅·커뮤니티 잡음이면 true")
    val isMarketingNoise: Boolean = false,
    @field:JsonPropertyDescription("그렇게 분류한 근거를 원문 표현을 인용해 한 문장으로. 최대 200자")
    val reason: String? = null,
)

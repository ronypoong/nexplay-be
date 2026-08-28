package com.rubion.nexplaybe.intelligence

import com.fasterxml.jackson.annotation.JsonPropertyDescription

/** 공식 발표 하나에서 뽑아낸 약속 하나. */
data class PromisedClaim(
    @field:JsonPropertyDescription(
        "약속의 종류. RELEASE_DATE(출시 시점), KOREAN_SUPPORT(한국어 지원), " +
            "CONTENT(DLC·업데이트·로드맵), PLATFORM(플랫폼 추가), DEMO(체험판) 중 하나. " +
            "어디에도 맞지 않으면 이 약속은 반환하지 않는다.",
    )
    val claimType: String = "",
    @field:JsonPropertyDescription("약속한 내용을 원문 그대로. 예: 'Fall 2026', '한국어 자막 지원', 'Patch 1.2'")
    val claimedValue: String = "",
    @field:JsonPropertyDescription("약속 시점의 시작(YYYY-MM-DD). 'Fall 2026'이면 2026-09-01. 시점이 없으면 null")
    val claimedFrom: String? = null,
    @field:JsonPropertyDescription("약속 시점의 끝(YYYY-MM-DD). 'Fall 2026'이면 2026-11-30. 시점이 없으면 null")
    val claimedTo: String? = null,
    @field:JsonPropertyDescription("시점의 정밀도: DAY, MONTH, QUARTER, SEASON, YEAR, NONE 중 하나")
    val claimPrecision: String = "NONE",
    @field:JsonPropertyDescription("이 약속의 근거가 된 원문 문장을 그대로 인용. 최대 300자")
    val sourceQuote: String = "",
)

/**
 * 발표 하나에서 뽑은 약속 목록.
 *
 * 약속이 없는 글이 대부분이다 — 그럴 땐 빈 배열을 반환하게 한다. 억지로 찾아내면
 * 대조표가 노이즈로 가득 찬다.
 */
data class PromiseExtraction(
    @field:JsonPropertyDescription("이 발표가 담고 있는 약속들. 미래 시점의 약속이 없으면 빈 배열")
    val promises: List<PromisedClaim> = emptyList(),
)

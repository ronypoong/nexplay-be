package com.rubion.nexplaybe.collector

import com.rubion.nexplaybe.event.GameEventType
import org.springframework.stereotype.Component

@Component
class EventClassifier {
    fun classify(title: String): GameEventType {
        val value = title.lowercase()
        return when {
            "release date" in value || "launch date" in value || "launches on" in value || "출시일" in value -> GameEventType.RELEASE_DATE
            "dlc" in value -> GameEventType.DLC
            "expansion" in value || "확장팩" in value -> GameEventType.EXPANSION
            "major update" in value || "enhanced" in value || "expedition" in value || "대규모 업데이트" in value || "주요 업데이트" in value -> GameEventType.MAJOR_UPDATE
            "patch" in value || "hotfix" in value || "update" in value || "패치" in value || "업데이트" in value -> GameEventType.PATCH
            "gameplay" in value -> GameEventType.GAMEPLAY
            "trailer" in value -> GameEventType.TRAILER
            "demo" in value -> GameEventType.DEMO
            "beta" in value || "playtest" in value -> GameEventType.BETA
            "delay" in value || "postpon" in value || "연기" in value -> GameEventType.DELAY
            "now available" in value || "out now" in value || "released" in value -> GameEventType.RELEASE
            else -> GameEventType.ANNOUNCEMENT
        }
    }

    fun localizedTitle(title: String): String = when {
        title.contains("Crimson Desert Enhanced Update Now Available", ignoreCase = true) -> "붉은사막 Enhanced 대규모 업데이트 출시"
        title.startsWith("Patch Notes Version ", ignoreCase = true) -> title.replace(Regex("(?i)^Patch Notes Version"), "패치 노트 버전")
        title.contains("Expansion", ignoreCase = true) -> title.replace(Regex("(?i)Expansion"), "확장팩")
        else -> title
    }

    fun normalizedTitle(title: String): String = title.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

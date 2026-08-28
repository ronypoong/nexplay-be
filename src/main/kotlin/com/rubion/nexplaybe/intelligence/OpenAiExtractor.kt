package com.rubion.nexplaybe.intelligence

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/**
 * 모델 호출 창구.
 *
 * SDK 를 쓰지 않고 직접 부른다. 이 서비스가 모델에게 시키는 일은 "정해진 스키마에
 * 맞춰 원문에서 뽑아내기" 하나뿐이라, SDK 가 얹어 주는 것보다 의존성 하나를 줄이는
 * 편이 낫다. foresee-be 에서 같은 방식이 이미 돌고 있다.
 *
 * 키가 없으면 전체가 꺼진다. 조용히 엉뚱한 값을 쓰느니 아무것도 안 하는 편이 낫다.
 */
@Component
class OpenAiExtractor(
    @param:Value("\${nexplay.intelligence.api-key:}") private val apiKey: String,
    @param:Value("\${nexplay.intelligence.model:gpt-5.4-mini}") val model: String,
    @param:Value("\${nexplay.intelligence.base-url:https://api.openai.com/v1}") private val baseUrl: String,
    @param:Value("\${nexplay.intelligence.daily-token-budget:300000}") private val dailyTokenBudget: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    val enabled: Boolean get() = apiKey.isNotBlank()

    private val restClient: RestClient by lazy {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(Duration.ofMinutes(2)) }
        RestClient.builder().requestFactory(factory).build()
    }

    // 상한을 두는 이유는 아끼려는 게 아니라, 버그나 설정 실수로 폭주할 때
    // 요금이 무한정 나가는 걸 막는 것이다. 하루가 바뀌면 저절로 풀린다.
    private val spentToday = AtomicLong(0)
    @Volatile private var budgetDate: LocalDate = LocalDate.now()

    @Synchronized
    private fun hasRoom(): Boolean {
        val today = LocalDate.now()
        if (today != budgetDate) {
            budgetDate = today
            spentToday.set(0)
        }
        return spentToday.get() < dailyTokenBudget
    }

    /**
     * 스키마에 맞춰 한 번 뽑는다.
     *
     * @param schema OpenAI json_schema 의 `schema` 부분. strict 모드라 모든 속성이
     *   required 여야 하고 additionalProperties 는 false 여야 한다.
     */
    fun <T> extract(
        schemaName: String,
        schema: Map<String, Any>,
        systemPrompt: String,
        userMessage: String,
        maxOutputTokens: Int,
        type: Class<T>,
    ): T? {
        if (!enabled) return null
        if (!hasRoom()) {
            log.warn("일일 토큰 예산({})을 넘어 모델 호출을 건너뜁니다.", dailyTokenBudget)
            return null
        }

        val body = mapOf(
            "model" to model,
            "max_completion_tokens" to maxOutputTokens,
            "response_format" to mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf("name" to schemaName, "strict" to true, "schema" to schema),
            ),
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userMessage),
            ),
        )

        val response = restClient.post()
            .uri("$baseUrl/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer $apiKey")
            .body(body)
            .retrieve()
            .body(ChatResponse::class.java) ?: return null

        response.usage?.let { spentToday.addAndGet(it.totalTokens.toLong()) }

        val choice = response.choices.firstOrNull() ?: return null
        // "length" 는 출력 상한에 걸려 잘렸다는 뜻이다. 잘린 JSON 은 파싱에 실패하고,
        // 그 실패가 왜 났는지 모르면 상한을 의심하기까지 한참 걸린다.
        if (choice.finishReason == "length") {
            log.warn("{} 응답이 출력 상한({})에 걸려 잘렸습니다.", schemaName, maxOutputTokens)
            return null
        }
        val content = choice.message.content.takeIf(String::isNotBlank) ?: return null
        return mapper.readValue(content, type)
    }

    /** 오늘 쓴 토큰. 관리 화면에서 확인용. */
    fun tokensSpentToday(): Long = spentToday.get()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatResponse(val choices: List<Choice> = emptyList(), val usage: Usage? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Choice(
        val message: Message = Message(),
        @param:JsonProperty("finish_reason") val finishReason: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Message(val content: String = "")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Usage(
        @param:JsonProperty("prompt_tokens") val promptTokens: Int = 0,
        @param:JsonProperty("completion_tokens") val completionTokens: Int = 0,
        @param:JsonProperty("total_tokens") val totalTokens: Int = 0,
    )
}

/** strict 모드 스키마를 만드는 잔손질. 속성 전부가 required 여야 한다는 규칙을 여기서 지킨다. */
object Schemas {
    fun obj(vararg properties: Pair<String, Map<String, Any>>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to properties.toMap(),
        "required" to properties.map { it.first },
        "additionalProperties" to false,
    )

    fun str(description: String): Map<String, Any> = mapOf("type" to "string", "description" to description)
    fun enumOf(description: String, vararg values: String): Map<String, Any> =
        mapOf("type" to "string", "description" to description, "enum" to values.toList())
    fun bool(description: String): Map<String, Any> = mapOf("type" to "boolean", "description" to description)

    /** 값이 없을 수 있는 자리. strict 모드에서는 required 를 뺄 수 없어 null 을 허용하는 식으로 표현한다. */
    fun nullableStr(description: String): Map<String, Any> =
        mapOf("type" to listOf("string", "null"), "description" to description)
    fun nullableInt(description: String): Map<String, Any> =
        mapOf("type" to listOf("integer", "null"), "description" to description)

    fun arrayOf(description: String, items: Map<String, Any>): Map<String, Any> =
        mapOf("type" to "array", "description" to description, "items" to items)
}

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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * 모델 호출 창구.
 *
 * SDK 를 쓰지 않고 직접 부른다. 이 서비스가 모델에게 시키는 일은 "정해진 스키마에
 * 맞춰 원문에서 뽑아내기" 하나뿐이라, SDK 가 얹어 주는 것보다 의존성 하나를 줄이는
 * 편이 낫다. foresee-be 에서 같은 방식이 이미 돌고 있다.
 *
 * 키가 없으면 전체가 꺼진다. 조용히 엉뚱한 값을 쓰느니 아무것도 안 하는 편이 낫다.
 */
/** 하루 토큰 상한에 걸렸다. 실패가 아니라 "오늘은 여기까지" 라는 뜻이다. */
class BudgetExhaustedException(budget: Long, spent: Long) :
    RuntimeException("일일 토큰 예산 소진: $spent / $budget")

@Component
class OpenAiExtractor(
    private val jdbc: JdbcTemplate,
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

    /**
     * 오늘 쓴 토큰. **DB 에서 읽는다.**
     *
     * 예전에는 메모리 카운터였다. 배포할 때마다 컨테이너가 새로 뜨면서 0 으로
     * 돌아갔고, 하루에 열 번 배포하면 상한이 열 번 풀렸다. 폭주를 막으려고 둔
     * 장치가 정작 폭주를 못 막았다.
     */
    fun tokensSpentToday(): Long = runCatching {
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(total_tokens), 0) FROM llm_usage WHERE usage_date = CURRENT_DATE",
            Long::class.java,
        ) ?: 0L
    }.getOrDefault(0L)

    private fun hasRoom(): Boolean = tokensSpentToday() < dailyTokenBudget

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
        // 예산 소진을 null 로 돌려주면 부르는 쪽에서 "약속이 없었다" 와 구분할 수 없다.
        // 실제로 배치 아홉 번이 아무 일도 안 하고 SUCCESS 를 보고한 적이 있다.
        if (!hasRoom()) throw BudgetExhaustedException(dailyTokenBudget, tokensSpentToday())

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

        // 기록에 실패해도 추출은 살린다. 다만 기록이 빠지면 상한이 헐거워지므로
        // 조용히 넘기지 않고 남긴다.
        response.usage?.let { usage ->
            runCatching {
                jdbc.update(
                    """
                    INSERT INTO llm_usage (usage_date, purpose, model, calls, prompt_tokens, completion_tokens, total_tokens)
                    VALUES (CURRENT_DATE, ?, ?, 1, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE calls = calls + 1,
                      prompt_tokens = prompt_tokens + VALUES(prompt_tokens),
                      completion_tokens = completion_tokens + VALUES(completion_tokens),
                      total_tokens = total_tokens + VALUES(total_tokens)
                    """.trimIndent(),
                    schemaName, model,
                    usage.promptTokens.toLong(), usage.completionTokens.toLong(), usage.totalTokens.toLong(),
                )
            }.onFailure { log.error("토큰 사용 기록 실패 — 상한이 헐거워진다: {}", it.message) }
        }

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

    val dailyBudget: Long get() = dailyTokenBudget

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

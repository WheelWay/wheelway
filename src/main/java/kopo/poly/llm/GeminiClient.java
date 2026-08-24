package kopo.poly.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gemini 로 목적지 이름을 뽑는다.
 *
 * <h3>키와 모델은 별개다</h3>
 * <pre>
 *   API 키   →  프로젝트 (인증·과금·한도)
 *   모델 ID  →  요청마다 지정
 * </pre>
 * 키 하나로 여러 모델을 부를 수 있고 <b>한도는 모델별로 따로</b> 걸린다.
 * 실수로 {@code gemini-3.6-flash} 를 부르면 하루 20회에 걸린다 —
 * 그래서 모델 이름을 {@code application.properties} 에 못박아 둔다.
 *
 * <h3>JSON 으로 받는다</h3>
 * {@code responseMimeType} + {@code responseSchema} 를 주면 스키마에 맞는 JSON 만 온다.
 * 문장으로 받아서 파싱하면 "청주시청으로 가시려는 것 같습니다" 같은 답을 잘라내야 하는데,
 * 그 잘라내기가 곧 새로운 오류원이 된다.
 *
 * <h3>키가 없으면</h3>
 * 앱은 그대로 뜬다. 챗봇에 말을 걸 때만 {@link LlmUnavailableException} 이 나고,
 * 부르는 쪽이 규칙 기반으로 떨어진다.
 */
@Slf4j
@Component
public class GeminiClient implements ILlmClient {

    private static final String API =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** {@code credentials/api_keys.properties} 의 {@code gemini.api-key}. 없으면 빈 문자열이다. */
    private final String apiKey;

    /** {@code application.properties} 의 {@code wheelway.llm-model}. */
    private final String model;

    private final Duration timeout;

    private final HttpClient http;

    public GeminiClient(@Value("${gemini.api-key:}") String apiKey,
                        @Value("${wheelway.llm-model}") String model,
                        @Value("${wheelway.llm-timeout-ms}") long timeoutMs) {

        this.apiKey = (apiKey == null) ? "" : apiKey.trim();
        this.model = model.trim();
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        if (this.apiKey.isEmpty()) {
            log.warn("gemini.api-key 가 없습니다. 챗봇이 규칙 기반으로만 동작합니다.");
        } else {
            log.info("길 안내 도우미 모델: {}", this.model);
        }
    }

    @Override
    public Destination extractDestination(String utterance, List<String> candidates) {
        if (apiKey.isEmpty()) {
            throw new LlmUnavailableException("길 안내 도우미가 설정되지 않았습니다. (gemini.api-key)");
        }

        String body = requestBody(prompt(utterance, candidates));

        HttpRequest req = HttpRequest.newBuilder(URI.create(API.formatted(model)))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        String raw;
        try {
            HttpResponse<String> res =
                    http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // 429 는 한도다. RPD 500 은 넉넉하지만 RPM 15 는 연타하면 걸린다.
            // 400 은 대개 모델 이름 오타다 — 사람이 읽을 수 있게 코드를 그대로 남긴다.
            if (res.statusCode() != 200) {
                throw new LlmUnavailableException(
                        "길 안내 도우미가 HTTP " + res.statusCode() + " 를 냈습니다.");
            }
            raw = res.body();

        } catch (LlmUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("길 안내 도우미 호출이 중단되었습니다.", e);
        } catch (Exception e) {
            // 타임아웃도 여기로 온다. 규칙 기반으로 떨어질 수 있게 같은 예외로 알린다.
            throw new LlmUnavailableException("길 안내 도우미에 연결하지 못했습니다.", e);
        }

        return parse(raw);
    }

    // ------------------------------------------------------------------ 프롬프트

    /**
     * <b>사용자의 말을 그대로 쓰게 하고, 후보는 글자를 고칠 때만 보게 한다.</b>
     *
     * <p>★ 처음에는 "후보에 있으면 그것을 골라라" 였는데 <b>엉뚱한 곳을 골랐다</b> —
     * {@code 청주시청 가는 길 알려줘} 에 <b>시청 신청사예정지</b> 가 나왔다(2026-08-21).
     * 모델 잘못이 아니라 시킨 대로 한 것이다. 후보는 <b>고쳐주는 목록</b>이지
     * 골라야 하는 목록이 아니다.
     *
     * <p>전사가 깨진 채로 와도({@code 청주 신의청}) 소리가 거의 같은 후보가 있으면 복구한다.
     * 이미 말이 되는 이름은 그대로 둔다.
     *
     * <p>거리·시간·경로를 절대 말하지 말라고 못박는다. 안 막으면 친절하게 지어낸다.
     */
    private static String prompt(String utterance, List<String> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                너는 휠체어 이동경로 안내 서비스의 '목적지 해석기'다.
                사용자가 한 말에서 가려는 곳의 이름 하나만 뽑아라.

                규칙
                - **사용자가 말한 이름을 그대로 쓰는 것이 원칙이다.**
                  '청주시청 가는 길 알려줘' 의 답은 '청주시청' 이다.
                - 아래 후보 목록은 **틀린 글자를 고칠 때만** 쓴다. 골라야 하는 목록이 아니다.
                  사용자의 말은 음성인식을 거쳐 글자가 틀려 있을 수 있는데, 그때 소리가
                  거의 같은 후보가 있으면 그것으로 고쳐라.
                    '청주 신의청' -> '청주시청'   (소리가 거의 같다. 고친다)
                    '성한 길로'   -> '성안길'     (같음)
                    '청주시청'    -> '청주시청'   (이미 말이 된다. 후보에 비슷한 것이
                                                  있어도 바꾸지 마라)
                - 후보에 없어도 된다. 이 지역에 있을 법한 곳이면 말에서 뽑은 이름을 그대로 써라.
                - 안내 지역과 관계없는 곳이거나(예: 부산역) 목적지를 알 수 없으면
                  matched 를 false 로 하라. 억지로 고르지 마라.
                - **거리·소요시간·경로·길 안내를 말하지 마라.** 그것은 네 일이 아니다.
                  목적지 이름 하나만 답하면 된다.
                """);

        if (!candidates.isEmpty()) {
            sb.append("\n후보 목록 (글자를 고칠 때만 참고. 여기서 고르라는 뜻이 아니다)\n");
            for (String c : candidates) {
                sb.append("- ").append(c).append('\n');
            }
        }

        sb.append("\n사용자의 말\n").append(utterance).append('\n');
        return sb.toString();
    }

    private static String requestBody(String prompt) {
        ObjectNode root = MAPPER.createObjectNode();

        ArrayNode parts = root.putArray("contents").addObject().putArray("parts");
        parts.addObject().put("text", prompt);

        ObjectNode cfg = root.putObject("generationConfig");
        // 같은 말에 같은 답이 나와야 한다. 목적지 해석에 창의성은 해롭기만 하다.
        cfg.put("temperature", 0);
        cfg.put("responseMimeType", "application/json");

        ObjectNode schema = cfg.putObject("responseSchema");
        schema.put("type", "OBJECT");
        ObjectNode props = schema.putObject("properties");
        props.putObject("destination").put("type", "STRING");
        props.putObject("matched").put("type", "BOOLEAN");
        schema.putArray("required").add("destination").add("matched");

        return root.toString();
    }

    // ------------------------------------------------------------------ 응답

    private static Destination parse(String raw) {
        JsonNode text = MAPPER.readTree(raw)
                .path("candidates").path(0)
                .path("content").path("parts").path(0)
                .path("text");

        if (text.isMissingNode()) {
            // 안전필터에 걸리거나 응답이 잘리면 여기로 온다. 빈 답을 '못 찾음' 으로
            // 돌리면 원인을 영영 모른다.
            throw new LlmUnavailableException("길 안내 도우미가 빈 답을 냈습니다.");
        }

        JsonNode out;
        try {
            out = MAPPER.readTree(text.asString());
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("길 안내 도우미의 답을 읽지 못했습니다.", e);
        }

        String destination = out.path("destination").asString();
        boolean matched = out.path("matched").asBoolean(false);

        // 스키마가 있어도 빈 문자열은 올 수 있다. 그건 못 찾은 것이다.
        if (destination == null || destination.isBlank()) {
            return new Destination("", false);
        }
        return new Destination(destination.trim(), matched);
    }
}

package kopo.poly.stt;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 옆에서 돌고 있는 whisper.cpp 서버에 WAV 를 넘겨 전사문을 받는다.
 *
 * <h3>왜 별도 프로세스인가</h3>
 * whisper.cpp 는 C++ 실행파일이라 스프링 안에 못 들어간다. {@code run-whisper.cmd} 로
 * 따로 띄우고 여기서 HTTP 로 부른다.
 *
 * <p><b>안 띄워도 앱은 그대로 돈다.</b> 챗봇에 타이핑으로 물으면 길을 찾아준다 —
 * 그래서 이걸 {@code run-dev.cmd} 에 묶지 않았다. 모델이 182MB 라 받아둔 사람만 띄우면 된다.
 *
 * <h3>포트가 8090 인 이유</h3>
 * whisper-server 의 기본 포트가 <b>8080</b> 이라 스프링과 정면으로 겹친다.
 * 그대로 두면 둘 중 하나가 조용히 안 뜬다.
 *
 * <h3>whisper-stream 을 안 쓰는 이유</h3>
 * 그 도구가 여는 마이크는 <b>서버 PC 의 마이크</b>다. 개발 중에는 서버도 브라우저도 내 PC 라
 * 잘 되는 것처럼 보이지만, 휴대폰으로 접속하면 서버 PC 방의 소리를 녹음하고 두 명이 동시에
 * 쓰면 마이크가 하나뿐이다. 게다가 이 CPU 로는 스트리밍이 실시간을 못 따라간다
 * (2초 오디오를 2.2초에 처리한다). {@code stream}·{@code command} 는 잘못된 도구가 아니라
 * <b>데스크톱 앱·키오스크용 도구</b>다.
 */
@Slf4j
@Component
public class WhisperCppClient implements ISttClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** multipart 경계 문자열. 오디오 바이트에 우연히 들어갈 일이 없게 길게 잡는다. */
    private static final String BOUNDARY = "----wheelway-whisper-boundary-7f3a1c";

    private final String url;

    /**
     * 요청마다 실어 보내는 언어.
     *
     * <p><b>요청에 실린 값이 서버 기동 옵션을 이긴다</b>(2026-08-21 실측). {@code language=en}
     * 을 보내면 서버를 {@code ko} 로 띄웠어도 {@code " (speaking in Korean)"} 한 줄이
     * <b>200 으로</b> 돌아온다 — 오류가 아니라 정상 응답이라 원인을 찾기가 아주 어렵다.
     * 이기는 쪽에서 못박아 두는 것이 확실하다.
     *
     * <p>★ 처음에는 "옵션 없이 띄우면 기본이 {@code en} 이라 깨진다" 고 적었는데
     * <b>재보니 그렇지 않았다.</b> {@code --language} 없이 띄우고 파라미터도 안 보냈더니
     * 1,990ms 에 한국어가 정상으로 나왔다. 도움말의 기본값 표시({@code [en]})와 실제
     * 동작이 다르다. 그러니 이 값은 '깨지는 것을 막는 것' 이 아니라
     * <b>'서버를 어떻게 띄웠든 같게 동작하게 못박는 것'</b> 이다.
     */
    private final String language;

    private final Duration timeout;
    private final HttpClient http;

    public WhisperCppClient(@Value("${wheelway.stt-url}") String url,
                            @Value("${wheelway.stt-language}") String language,
                            @Value("${wheelway.stt-timeout-ms}") long timeoutMs) {
        this.url = url.trim();
        this.language = language.trim();
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        log.info("음성인식 서버: {} · language={} (안 떠 있어도 앱은 뜬다 — 타이핑으로 물으면 된다)",
                this.url, this.language);
    }

    @Override
    public boolean available() {
        // 빈 요청을 보내 살아 있는지만 본다. 400 이 와도 '떠 있다' 는 뜻이라 성공으로 친다 —
        // 알고 싶은 것은 응답 내용이 아니라 연결 여부다.
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(600))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String transcribe(byte[] wav) {
        if (wav == null || wav.length == 0) {
            return "";
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(wav)))
                .build();

        String raw;
        try {
            HttpResponse<String> res =
                    http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (res.statusCode() != 200) {
                throw new SttUnavailableException(
                        "음성인식 서버가 HTTP " + res.statusCode() + " 를 냈습니다.");
            }
            raw = res.body();

        } catch (SttUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SttUnavailableException("음성인식이 중단되었습니다.", e);
        } catch (Exception e) {
            // 연결 거부가 여기로 온다 — run-whisper.cmd 를 안 띄운 경우다.
            throw new SttUnavailableException("음성인식 서버에 연결하지 못했습니다.", e);
        }

        // 응답은 {"text":" 청주시청 가는 길 알려줘\n"} 모양이다.
        // ★ 앞에 공백이 하나 붙고 뒤에 줄바꿈이 붙는다. 그대로 두면 목적지 해석까지 흘러간다.
        String text = MAPPER.readTree(raw).path("text").asString();
        return (text == null) ? "" : text.trim();
    }

    /**
     * {@code file} 한 칸짜리 multipart 본문.
     *
     * <p>직접 만드는 이유: {@code java.net.http} 에는 multipart 발행기가 없다. 파일 하나를
     * 보내려고 HTTP 클라이언트를 하나 더 끌어들일 이유가 없다.
     *
     * <p><b>바이트로 이어붙인다.</b> 오디오를 문자열에 담으면 인코딩을 거치면서 깨진다.
     */
    private byte[] multipart(byte[] wav) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(wav.length + 512);

        String head = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n";

        // response_format=json 을 줘야 {"text":...} 로 온다. 기본은 평문이라 파싱할 것이 없다.
        //
        // ★ language 는 서버 기동 옵션을 덮어쓴다 — 그러라고 보내는 것이다(위 필드 주석 참고).
        //   보내는 값과 안 보내는 값의 속도 차이는 없다(1,822ms vs 1,990ms — 흔들림 범위).
        String tail = "\r\n--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"response_format\"\r\n\r\n"
                + "json\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"language\"\r\n\r\n"
                + language + "\r\n"
                + "--" + BOUNDARY + "--\r\n";

        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(wav);
        out.writeBytes(tail.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}

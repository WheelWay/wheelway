package kopo.poly.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpSession;
import kopo.poly.service.IChatService;
import kopo.poly.stt.ISttClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 길 안내 도우미.
 *
 * <h3>왜 글부터인가</h3>
 * 챗봇 창에는 마이크 말고 <b>입력칸도 있다.</b> 그래서 whisper 를 띄우지 않고도
 * 목적지 해석 → 좌표 → 길찾기 → 답 문장까지 전부 검증할 수 있다.
 * 음성은 이 위에 앞단만 갈아끼우는 일이라({@code /api/chat/voice}),
 * <b>어려운 것과 새로운 것을 섞지 않으려고</b> 이 순서로 붙인다.
 *
 * <h3>왜 200 만 내는가</h3>
 * 실패해도 사람이 읽을 문장으로 답한다. 챗봇이 4xx·5xx 를 내면 화면은 말풍선에 띄울 말이
 * 없어져서 "오류가 발생했습니다" 밖에 못 쓴다 — 그건 사용자가 다음에 무엇을 해야 할지
 * 알려주지 못한다. 무엇이 잘못됐는지는 {@code answer} 안에 있다.
 *
 * <h3>로그인</h3>
 * <b>막지 않는다.</b> 로그인하면 등록해둔 집이 출발지가 되고, 아니면 화면이 준 현재위치를 쓴다.
 * 둘 다 없을 때만 되묻는다({@code needStart}). {@code WalkController} 처럼 401 로 막으면
 * 비로그인 사용자는 챗봇을 아예 못 써보는데, 여기는 남기는 것이 없어서 그럴 이유가 없다.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final IChatService chatService;
    private final ISttClient sttClient;

    /**
     * 글로 물었을 때.
     *
     * @param text 사용자가 친 문장
     * @param lat  화면이 이미 정해둔 출발지. 주면 등록해둔 집보다 이것을 쓴다
     * @param lng  같음
     */
    @PostMapping("/text")
    public ResponseEntity<IChatService.Answer> text(HttpSession session,
                                                    @RequestParam String text,
                                                    @RequestParam(required = false) Double lat,
                                                    @RequestParam(required = false) Double lng) {

        Object uid = session.getAttribute("SS_USER_ID");
        String username = (uid == null || String.valueOf(uid).isBlank()) ? null : String.valueOf(uid);

        long begin = System.currentTimeMillis();
        IChatService.Answer answer = chatService.ask(username, text, lat, lng);

        // 시간 예산을 눈으로 보려고 남긴다. 목표는 글 입력 기준 1.5초 안쪽이고,
        // 음성이 붙으면 여기에 whisper 1.9초가 앞에 더해진다.
        log.info("챗봇 '{}' → {} ({}ms)",
                text, answer.destination(), System.currentTimeMillis() - begin);

        return ResponseEntity.ok(answer);
    }

    /**
     * 말소리로 물었을 때.
     *
     * <p>글 쪽({@link #text})과 <b>뒤가 완전히 같다.</b> 여기서 새로 하는 일은 전사뿐이고,
     * 목적지 해석·좌표·길찾기·답 문장은 이미 검증된 그 길을 그대로 탄다.
     *
     * <p><b>16kHz · mono · 16bit PCM WAV</b> 를 받는다. 브라우저가 그 형식으로 만들어 보낸다 —
     * {@code MediaRecorder} 를 쓰면 webm/opus 가 나오고, 그러면 whisper 쪽에 ffmpeg 를
     * 깔아야 한다({@code --convert}). 팀원마다 깔아야 하는 것을 하나라도 줄이는 편이 낫다.
     *
     * @param audio 녹음한 WAV. 10초 자동 종료 기준 약 320KB 다
     */
    @PostMapping("/voice")
    public ResponseEntity<IChatService.Answer> voice(HttpSession session,
                                                     @RequestParam("audio") MultipartFile audio,
                                                     @RequestParam(required = false) Double lat,
                                                     @RequestParam(required = false) Double lng) {

        Object uid = session.getAttribute("SS_USER_ID");
        String username = (uid == null || String.valueOf(uid).isBlank()) ? null : String.valueOf(uid);

        byte[] wav;
        try {
            wav = audio.getBytes();
        } catch (IOException e) {
            // 업로드가 중간에 끊긴 경우다. 사용자가 다시 말하면 되는 일이라 그렇게 답한다.
            log.warn("음성 업로드 읽기 실패", e);
            return ResponseEntity.ok(new IChatService.Answer(
                    "녹음을 받지 못했습니다. 다시 말씀해 주세요.",
                    "", null, false, null, null, null, 0, 0, false, "UNKNOWN", false));
        }

        long begin = System.currentTimeMillis();
        IChatService.Answer answer = chatService.askVoice(username, wav, lat, lng);

        // 시간 예산을 눈으로 본다. whisper 1.8초 + Gemini 1.0초 + 나머지 0.2초 = 약 3초가 목표다.
        log.info("챗봇(음성) {}바이트 → '{}' → {} ({}ms)",
                wav.length, answer.heard(), answer.destination(), System.currentTimeMillis() - begin);

        return ResponseEntity.ok(answer);
    }

    /**
     * 마이크를 열어도 되는 상태인가.
     *
     * <p>화면이 이걸 먼저 물어본다. whisper 를 안 띄운 채로 마이크를 눌러보게 한 뒤에야
     * 안 된다고 말하면 <b>사용자는 자기 마이크를 의심한다</b> — 실제로 그 자리에서
     * '이어폰이 연결이 안 되나' 를 먼저 확인하게 된다.
     */
    @GetMapping("/voice/available")
    public Map<String, Object> voiceAvailable() {
        return Map.of("available", sttClient.available());
    }
}

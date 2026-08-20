package kopo.poly.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;
import kopo.poly.dto.WalkRecordDTO;
import kopo.poly.service.IWalkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이동 기록과 개인 속도.
 *
 * <p>이 화면의 목적은 <b>"몇 시에 나가야 하나"에 그 사람의 실제 속도를 쓰는 것</b>이다.
 * 안내가 11분인데 실제로 20분 걸리면 늦는 것은 사용자다.
 *
 * <p>역산(도착 목표 → 출발 시각)은 서버가 하지 않는다. 거리와 속도만 주면
 * 화면에서 바로 나오는 계산이고, 사용자가 목표 시각을 만질 때마다 서버를 부를 이유가 없다.
 *
 * <p><b>전부 로그인 전용이다</b>(2026-08-18 결정). 기록이 {@code USERS} 로 FK 가 걸려 있어
 * 애초에 익명으로는 남길 수 없고, 조회만 열어두면 '보이는데 저장은 안 되는' 상태가 된다.
 * 비로그인은 빈 값이 아니라 <b>401</b> 로 답한다 — 화면이 '기록이 없음'과
 * '로그인이 안 됨'을 구분해서 말할 수 있어야 한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/walk")
@RequiredArgsConstructor
public class WalkController {

    private final IWalkService walkService;

    private static String loginId(HttpSession session) {
        Object v = session.getAttribute("SS_USER_ID");
        return (v == null || String.valueOf(v).isBlank()) ? null : String.valueOf(v);
    }

    /** 비로그인이면 401 과 사유. 빈 값으로 답하면 화면이 '기록이 없다'고 잘못 말한다. */
    private static ResponseEntity<Map<String, Object>> needLogin() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("ok", false, "message", "로그인이 필요합니다."));
    }

    /**
     * 안내에 쓸 값.
     *
     * @param stop 기준 정류장 이름. 안 주면 '정류장을 안 고르고 잰 기록' 묶음이다.
     *             <b>전체가 아니다</b> — 섞으면 중앙값이 틀린 값이 된다
     */
    @GetMapping("/speed")
    public ResponseEntity<Map<String, Object>> speed(HttpSession session,
                                                     @RequestParam(required = false) String stop) {
        String username = loginId(session);
        if (username == null) {
            return needLogin();
        }
        return ResponseEntity.ok(speedMap(walkService.speedOf(username, blankToNull(stop))));
    }

    /**
     * 빈 문자열은 {@code null} 로 본다.
     *
     * <p>화면이 정류장을 안 골랐을 때 {@code stop=} 을 빈 값으로 보내는데, 그대로 두면
     * DB 의 {@code NULL} 묶음과 {@code ''} 묶음이 갈려서 같은 상황이 두 통으로 나뉜다.
     */
    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /** {@code typicalMinutes} 는 기록이 없으면 null 이라 Map.of 를 못 쓴다. */
    private static Map<String, Object> speedMap(IWalkService.Speed s) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("mPerMin", s.mPerMin());
        m.put("typicalMinutes", s.typicalMinutes());
        m.put("personalized", s.personalized());
        m.put("recordCount", s.recordCount());
        m.put("minRecords", s.minRecords());

        // 도보 대비 배수. null 이면 '아직 모른다' 다 — 화면이 1.0 으로 넘겨짚지 않게 그대로 넘긴다.
        m.put("walkRatio", s.walkRatio());
        m.put("ratioCount", s.ratioCount());
        m.put("ratioLow", s.ratioLow());
        m.put("ratioHigh", s.ratioHigh());
        return m;
    }

    /** 그 정류장까지 잰 기록. 제외 표시한 것까지 준다(되돌릴 수 있어야 한다). */
    @GetMapping
    public ResponseEntity<?> list(HttpSession session,
                                  @RequestParam(required = false) String stop) {
        String username = loginId(session);
        if (username == null) {
            return needLogin();
        }
        return ResponseEntity.ok(walkService.list(username, blankToNull(stop)));
    }

    /**
     * 도착해서 [도착] 을 눌렀을 때 한 건을 남긴다.
     *
     * <p>진행 중인 이동은 서버에 두지 않는다. [출발] 만 누르고 안 돌아오는 일이 반드시 생기는데,
     * 그때 '도착 안 한 행'이 영영 남아 따로 치워야 한다. 진행 중 상태는 브라우저가 들고 있다가
     * 완결된 것만 여기로 보낸다.
     *
     * @param startedAt {@code yyyy-MM-dd HH:mm:ss}
     * @param arrivedAt {@code yyyy-MM-dd HH:mm:ss}
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> record(HttpSession session,
                                                      @RequestParam(required = false) Double startLat,
                                                      @RequestParam(required = false) Double startLng,
                                                      @RequestParam(required = false) Double endLat,
                                                      @RequestParam(required = false) Double endLng,
                                                      @RequestParam(required = false) Double distanceM,
                                                      @RequestParam(required = false) String stop,
                                                      @RequestParam String startedAt,
                                                      @RequestParam String arrivedAt) {

        String username = loginId(session);
        if (username == null) {
            return needLogin();
        }

        WalkRecordDTO dto = new WalkRecordDTO();
        dto.setUsername(username);
        dto.setStartLat(startLat);
        dto.setStartLng(startLng);
        dto.setEndLat(endLat);
        dto.setEndLng(endLng);
        dto.setDistanceM(distanceM);
        dto.setStopName(blankToNull(stop));
        dto.setStartedAt(startedAt);
        dto.setArrivedAt(arrivedAt);

        try {
            walkService.record(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        }

        // 저장 직후의 값을 같이 준다. 이번 기록이 반영된 '평소 몇 분'을 화면이 바로 쓸 수 있다.
        // 방금 저장한 정류장 기준이어야 한다 — 다른 묶음의 값을 주면 화면이 엉뚱한 숫자를 띄운다.
        Map<String, Object> out = speedMap(walkService.speedOf(username, dto.getStopName()));
        out.put("ok", true);
        out.put("id", dto.getId());
        return ResponseEntity.ok(out);
    }

    /**
     * 기록을 평균에서 빼거나 되돌린다. 지우지 않는다 — 왜 뺐는지가 남아야 한다.
     *
     * @param stop 되돌려줄 안내 값의 기준 정류장. 화면이 보고 있는 묶음을 그대로 넘긴다
     */
    @PutMapping("/{id}/excluded")
    public ResponseEntity<Map<String, Object>> excluded(HttpSession session,
                                                        @PathVariable long id,
                                                        @RequestParam boolean excluded,
                                                        @RequestParam(required = false) String stop) {

        String username = loginId(session);
        if (username == null) {
            return needLogin();
        }

        int n = walkService.setExcluded(id, username, excluded);
        if (n == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("ok", false, "message", "기록 #" + id + " 를 찾지 못했습니다."));
        }

        Map<String, Object> out = speedMap(walkService.speedOf(username, blankToNull(stop)));
        out.put("ok", true);
        return ResponseEntity.ok(out);
    }

    /**
     * 기록을 아주 지운다. <b>되돌릴 수 없다.</b>
     *
     * <p>빼기와 따로 둔 이유: [도착] 누르는 걸 잊어 1시간이 찍힌 것은 이동 기록이 아니다.
     * 서버가 거르는 것은 4시간이 넘는 것뿐이라 1시간짜리는 정상 범위로 통과해
     * 중앙값을 그대로 끌어당긴다. 그런 것은 빼두는 게 아니라 없애는 것이 맞다.
     *
     * <p>확인은 화면이 묻는다 — 서버가 두 번 부르게 만들면 그 사이에 상태가 갈릴 수 있다.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(HttpSession session,
                                                      @PathVariable long id,
                                                      @RequestParam(required = false) String stop) {

        String username = loginId(session);
        if (username == null) {
            return needLogin();
        }

        int n = walkService.delete(id, username);
        if (n == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("ok", false, "message", "기록 #" + id + " 를 찾지 못했습니다."));
        }

        Map<String, Object> out = speedMap(walkService.speedOf(username, blankToNull(stop)));
        out.put("ok", true);
        return ResponseEntity.ok(out);
    }
}

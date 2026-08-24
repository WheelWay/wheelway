package kopo.poly.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;
import kopo.poly.dto.UserPlaceDTO;
import kopo.poly.geocode.GeocodeUnavailableException;
import kopo.poly.service.IUserPlaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 사용자가 등록해둔 장소 — 지도 왼쪽 위 '집 / 회사 / 자주가는 경로'.
 *
 * <p><b>전부 로그인 전용이다.</b> {@code WalkController} 와 같은 이유다 — 조회만 열어두면
 * '보이는데 저장은 안 되는' 상태가 된다. 비로그인은 빈 값이 아니라 <b>401</b> 로 답한다.
 * 화면이 '등록한 집이 없음' 과 '로그인이 안 됨' 을 구분해서 말할 수 있어야 한다.
 *
 * <h3>실패를 세 가지로 나눠서 답한다</h3>
 * <pre>
 *   400  주소를 못 찾음 / 안내 지역 밖      사용자가 고칠 수 있다 — 다시 고르면 된다
 *   503  주소 검색 자체가 안 됨(키·한도)     사용자가 못 고친다 — 우리가 고쳐야 한다
 *   401  로그인이 안 됨                     로그인하면 된다
 * </pre>
 * 셋을 하나로 뭉뚱그리면 화면이 "주소를 찾지 못했습니다" 라고만 말하게 되고,
 * 사용자는 멀쩡한 자기 집 주소를 계속 다시 입력한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
public class UserPlaceController {

    private final IUserPlaceService placeService;

    private static String loginId(HttpSession session) {
        Object v = session.getAttribute("SS_USER_ID");
        return (v == null || String.valueOf(v).isBlank()) ? null : String.valueOf(v);
    }

    private static ResponseEntity<Map<String, Object>> needLogin() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("ok", false, "message", "로그인이 필요합니다."));
    }

    /** 등록해둔 장소 전부. 안내 지역 밖인 것도 {@code usable=false} 로 함께 준다. */
    @GetMapping
    public ResponseEntity<?> list(HttpSession session) {
        String username = loginId(session);
        if (username == null) {
            return needLogin();
        }
        List<UserPlaceDTO> places = placeService.list(username);
        return ResponseEntity.ok(Map.of("ok", true, "places", places));
    }

    /**
     * 한 종류만. 집 버튼이 눌렸을 때 부른다.
     *
     * <p>없으면 <b>404 가 아니라 200 + {@code place: null}</b> 이다. '아직 등록 안 함' 은
     * 오류가 아니라 정상 상태이고, 화면은 그때 등록 창을 연다.
     */
    @GetMapping("/{placeType}")
    public ResponseEntity<?> one(HttpSession session, @PathVariable String placeType) {
        String username = loginId(session);
        if (username == null) {
            return needLogin();
        }
        try {
            UserPlaceDTO place = placeService.get(username, placeType);
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            out.put("ok", true);
            out.put("place", place);       // null 일 수 있어서 Map.of 를 못 쓴다
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        }
    }

    /**
     * 등록하거나 덮어쓴다.
     *
     * <p><b>좌표를 받지 않는다.</b> 화면은 주소만 보내고 좌표는 서버가 만든다. 화면이 보낸
     * 좌표를 믿으면 지역 검증이 무의미해지고(아무 값이나 보낼 수 있다), 카카오 REST 키를
     * 브라우저에 내보내야 한다.
     *
     * @param address 카카오 우편번호 API 의 {@code data.address} 원문.
     *                우편번호·상세주소는 {@code zonecode}/{@code detail} 로 따로 보낸다 —
     *                합쳐 보내도 카카오가 견디는 것은 확인했지만(2026-08-21, 8/8),
     *                좌표에 보태는 것이 없어서 원문만 넘긴다
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(HttpSession session,
                                                    @RequestParam String placeType,
                                                    @RequestParam String address,
                                                    @RequestParam(required = false) String detail,
                                                    @RequestParam(required = false) String zonecode,
                                                    @RequestParam(required = false) String label) {

        String username = loginId(session);
        if (username == null) {
            return needLogin();
        }

        try {
            UserPlaceDTO saved = placeService.save(username, placeType, address, detail, zonecode, label);
            return ResponseEntity.ok(Map.of("ok", true, "place", saved));

        } catch (IllegalArgumentException e) {
            // 사용자가 고칠 수 있는 것 — 주소를 못 찾았거나 안내 지역 밖이다.
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));

        } catch (GeocodeUnavailableException e) {
            // 사용자가 못 고치는 것 — 키가 없거나 카카오가 죽었다. 그렇게 말해야 한다.
            log.warn("주소 검색 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "message", e.getMessage()));
        }
    }

    /**
     * 등록을 지운다. 되돌릴 수 없다. 확인은 화면이 묻는다.
     *
     * <p><b>지금 부르는 화면이 없다. 죽은 코드가 아니라 아직 안 붙인 것이다.</b>
     * 지도의 집 버튼은 등록돼 있으면 창을 띄우지 않고 곧장 출발지로 넣는 동작이라
     * 수정·삭제를 걸 자리가 없다. 그건 마이페이지가 생기면 거기서 한다(2026-08-21 결정).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(HttpSession session, @PathVariable long id) {
        String username = loginId(session);
        if (username == null) {
            return needLogin();
        }
        int n = placeService.delete(id, username);
        if (n == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("ok", false, "message", "등록된 장소를 찾지 못했습니다."));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}

package kopo.poly.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;
import kopo.poly.dto.ObstacleReportDTO;
import kopo.poly.graph.BlockedEdges;
import kopo.poly.service.IReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 장애물 제보 REST 엔드포인트.
 *
 * <p>{@link RouteController} 와 나눈 이유는 관심사가 다르고 저쪽이 이미 450 줄이라서다.
 * 좌표를 찍으면 무엇이 막히는지 미리 보는 기능은 <b>{@code /api/construction/preview} 를 그대로 쓴다</b> —
 * 이름만 공사용이고 안에서 하는 일은 {@code GraphHolder} 의 매칭 규칙 그 자체라, 제보에도 똑같이 맞다.
 * 같은 규칙을 두 벌 두면 화면에 보이는 것과 실제 차단이 어긋나기 시작한다.
 *
 * <p><b>지금 권한 검사는 로그인 여부까지다.</b> 확정·반려는 원래 관리자만 해야 하지만
 * {@code admin.html} 자체가 아직 아무나 열 수 있는 화면이고(공사 좌표도 그렇다),
 * 가입 기본 ROLE 이 {@code USER} 라 여기서 ADMIN 을 강제하면 검증조차 못 한다.
 * 화면에 인증을 붙일 때 같이 조여야 하는 자리다.
 */
@Slf4j
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final IReportService reportService;
    private final BlockedEdges blockedEdges;

    /** 로그인한 아이디. {@code UserController} 가 로그인 성공 시 넣어두는 값이다. */
    private static String loginId(HttpSession session) {
        Object v = session.getAttribute("SS_USER_ID");
        return (v == null || String.valueOf(v).isBlank()) ? null : String.valueOf(v);
    }

    /**
     * 제보 전체. 정도·상태를 가리지 않고 준다 — 걸러내는 것은 화면 쪽 일이다.
     *
     * <p>낮음을 뺀 각 건에는 실제로 막는(막게 될) 보도 구간이 {@code segments} 로 붙는다.
     */
    @GetMapping
    public List<ObstacleReportDTO> list() {
        return reportService.list();
    }

    /**
     * 제보 등록. <b>높음이면 이 요청 안에서 차단까지 반영된다</b> — 다음 경로 검색부터 그 구간을 피한다.
     *
     * @param radiusM {@code null} 이면 전역 설정값을 쓴다. 사용자 화면은 5 / 15 / 30 중 하나를 보내고,
     *                어느 쪽이든 서버가 {@code wheelway.report-max-radius-m} 로 다시 검사한다
     */
    @PostMapping
    public Map<String, Object> register(HttpSession session,
                                        @RequestParam double lat,
                                        @RequestParam double lng,
                                        @RequestParam String severity,
                                        @RequestParam String type,
                                        @RequestParam(required = false) String description,
                                        @RequestParam(required = false) Double radiusM) {

        String username = loginId(session);
        if (username == null) {
            // OBSTACLE_REPORTS.USERNAME 이 USERS 로 FK NOT NULL 이라, 비로그인 제보는 스키마상 불가능하다.
            return Map.of("ok", false, "message", "로그인이 필요합니다.");
        }

        ObstacleReportDTO dto = new ObstacleReportDTO();
        dto.setUsername(username);
        dto.setLatitude(lat);
        dto.setLongitude(lng);
        dto.setSeverity(severity);
        dto.setObstacleType(type);
        dto.setDescription(description == null || description.isBlank() ? null : description);
        dto.setBlockRadiusM(radiusM);

        int before = blockedEdges.size();
        ObstacleReportDTO saved;
        try {
            saved = reportService.register(dto);
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }

        int segCount = saved.getSegments() == null ? 0 : saved.getSegments().size();

        // 등록은 됐는데 아무것도 안 막는 경우를 알려줘야 한다. 좌표가 보도에서 멀 때 그렇게 된다.
        // 성공만 띄우면 사람은 막힌 줄 알고 넘어간다 — 공사구간에서 이미 겪은 함정이다.
        return Map.of("ok", true,
                "id", saved.getId(),
                "segmentCount", segCount,
                "blockedBefore", before,
                "blockedAfter", blockedEdges.size());
    }

    /**
     * 제보의 좌표·반경을 고친다. 관리자가 잘못 찍힌 위치나 과하게 잡힌 범위를 바로잡는 용도다.
     *
     * <p>고친 뒤 차단 Set 을 다시 계산한다 — 옮기기 전 자리를 막고 있던 엣지가 남으면 안 된다.
     */
    @PutMapping("/{id}/location")
    public Map<String, Object> updateLocation(HttpSession session,
                                              @PathVariable long id,
                                              @RequestParam double lat,
                                              @RequestParam double lng,
                                              @RequestParam(required = false) Double radiusM) {

        if (loginId(session) == null) {
            return Map.of("ok", false, "message", "로그인이 필요합니다.");
        }

        int before = blockedEdges.size();
        int n;
        try {
            n = reportService.updateLocation(id, lat, lng, radiusM);
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }

        if (n == 0) {
            return Map.of("ok", false, "message", "제보 #" + id + " 를 찾지 못했습니다.");
        }
        return Map.of("ok", true, "blockedBefore", before, "blockedAfter", blockedEdges.size());
    }

    /**
     * 확정(공개) / 반려 / 해소 처리.
     *
     * <p>반려는 오탐 판정이다. 차단 조회가 {@code STATUS IN ('대기','공개')} 라
     * 상태만 바꾸면 그 즉시 차단에서 빠진다.
     */
    @PutMapping("/{id}/status")
    public Map<String, Object> updateStatus(HttpSession session,
                                            @PathVariable long id,
                                            @RequestParam String status) {

        String admin = loginId(session);
        if (admin == null) {
            return Map.of("ok", false, "message", "로그인이 필요합니다.");
        }

        int before = blockedEdges.size();
        int n;
        try {
            n = reportService.updateStatus(id, status, admin);
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }

        if (n == 0) {
            return Map.of("ok", false, "message", "제보 #" + id + " 를 찾지 못했습니다.");
        }
        return Map.of("ok", true, "blockedBefore", before, "blockedAfter", blockedEdges.size());
    }
}

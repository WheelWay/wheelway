package kopo.poly.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import kopo.poly.dto.ObstacleReportDTO;
import kopo.poly.graph.BlockedEdges;
import kopo.poly.graph.GraphHolder;
import kopo.poly.mapper.IReportMapper;
import kopo.poly.service.IReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 장애물 제보.
 *
 * <p><b>차단 반영을 등록과 상태변경에서 다르게 하는 것</b>이 이 클래스에서 가장 중요한 판단이다.
 *
 * <pre>
 *   등록(높음)   blockedEdges.block(엣지들)      — 더하기만 한다. 빠르고, 겹쳐도 안전하다
 *   상태변경     graphHolder.reloadBlockedEdges() — 전부 다시 계산한다
 *   좌표·반경 수정  같음
 * </pre>
 *
 * <p>왜 빼는 쪽만 전체 재계산인가: <b>엣지 하나를 여러 사유가 함께 막고 있을 수 있다.</b>
 * 공사구간과 제보가 같은 보도를 덮거나, 제보 두 건의 반경이 겹치는 일은 흔하다.
 * 반려된 제보의 엣지를 {@code unblock()} 으로 지우면 <b>아직 유효한 다른 사유까지 같이 풀린다.</b>
 * 더하기는 그런 문제가 없어서 즉시 반영해도 된다.
 *
 * <p>비대칭이 정당한 이유가 하나 더 있다 — 등록은 사용자가 하는 잦은 동작이고,
 * 확정·반려는 관리자가 가끔 하는 동작이다. 무거운 쪽을 드문 쪽에 두는 게 맞다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService implements IReportService {

    private final IReportMapper reportMapper;
    private final GraphHolder graphHolder;
    private final BlockedEdges blockedEdges;

    /**
     * 제보 하나가 막을 수 있는 최대 반경(m). <b>사용자가 보낸 값을 그대로 믿지 않는다.</b>
     *
     * <p>높음 제보는 등록 즉시 하드필터에 들어간다. 반경을 사용자가 정하는데 상한이 없으면
     * 한 사람이 제보 한 건으로 일대의 보도를 통째로 막을 수 있다. 악의가 없어도 그렇게 된다 —
     * "확실히 막고 싶으니 크게" 가 자연스러운 선택이라 다들 최댓값을 고른다.
     */
    @Value("${wheelway.report-max-radius-m}")
    private double maxRadiusM;

    /** 화면에서 고를 수 있는 값. 여기 없는 값이 들어오면 거절한다. */
    private static final Set<String> SEVERITIES = Set.of(
            ObstacleReportDTO.SEV_LOW, ObstacleReportDTO.SEV_MEDIUM, ObstacleReportDTO.SEV_HIGH);

    /**
     * 받을 유형. <b>화면의 &lt;select&gt; 와 반드시 같아야 한다</b> —
     * 화면에만 늘리면 서버가 "유형은 [...] 중 하나여야 합니다" 로 거절한다.
     * 지금 목록을 쓰는 화면이 셋이다: map.html(제보 탭) · report.html · /admin(관리자 화면).
     */
    private static final Set<String> TYPES = Set.of("계단", "턱", "적치물", "공사", "기타");

    private static final Set<String> STATUSES = Set.of(
            ObstacleReportDTO.ST_PENDING, ObstacleReportDTO.ST_OPEN,
            ObstacleReportDTO.ST_RESOLVED, ObstacleReportDTO.ST_REJECTED);

    @Override
    public List<ObstacleReportDTO> list() {
        List<ObstacleReportDTO> rows = reportMapper.getReports(graphHolder.getRegionId());
        rows.forEach(r -> {
            if (!ObstacleReportDTO.SEV_LOW.equals(r.getSeverity())) {
                r.setSegments(graphHolder.matchSegments(r.getLatitude(), r.getLongitude(), radiusOf(r)));
            }
        });
        return rows;
    }

    @Override
    public ObstacleReportDTO register(ObstacleReportDTO dto) {
        validate(dto);

        dto.setRegionId(graphHolder.getRegionId());
        dto.setEdgeId(null);          // 항상 NULL. 이유는 ObstacleReportDTO 클래스 주석 참고
        dto.setPhotoUrl(null);        // 사진 저장소가 아직 없다

        reportMapper.insertReport(dto);

        double radius = radiusOf(dto);
        int blockedNow = 0;

        if (ObstacleReportDTO.SEV_HIGH.equals(dto.getSeverity())) {
            Set<Long> edges = graphHolder.matchEdges(dto.getLatitude(), dto.getLongitude(), radius);
            blockedEdges.block(edges);
            blockedNow = edges.size();
        }

        if (!ObstacleReportDTO.SEV_LOW.equals(dto.getSeverity())) {
            dto.setSegments(graphHolder.matchSegments(dto.getLatitude(), dto.getLongitude(), radius));
        }

        log.info("제보 #{} 등록 — {} / {} ({}, {}) 반경 {}m, 즉시 차단 {}엣지",
                dto.getId(), dto.getSeverity(), dto.getObstacleType(),
                dto.getLatitude(), dto.getLongitude(), radius, blockedNow);

        return dto;
    }

    @Override
    public int updateLocation(long id, double lat, double lng, Double radiusM) {
        checkRadius(radiusM);

        int n = reportMapper.updateReportLocation(id, graphHolder.getRegionId(), lat, lng, radiusM);
        if (n > 0) {
            // 옮기기 전 자리를 막고 있던 엣지가 남으면 안 된다. 더하기로는 지울 수 없으니 전체 재계산이다.
            graphHolder.reloadBlockedEdges();
            log.info("제보 #{} 좌표·반경 수정 — ({}, {}) 반경 {}, 차단 {}엣지",
                    id, lat, lng, radiusM == null ? "전역값" : radiusM + "m", blockedEdges.size());
        }
        return n;
    }

    @Override
    public int updateStatus(long id, String status, String verifiedBy) {
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("status 는 " + STATUSES + " 중 하나여야 합니다: " + status);
        }

        int n = reportMapper.updateReportStatus(id, graphHolder.getRegionId(), status, verifiedBy);
        if (n > 0) {
            // 반려·해소면 차단이 풀려야 하고, 공개면 다시 걸려야 한다. 어느 방향이든 재계산이 정확하다.
            graphHolder.reloadBlockedEdges();
            log.info("제보 #{} → '{}' ({} 처리), 차단 {}엣지", id, status, verifiedBy, blockedEdges.size());
        }
        return n;
    }

    @Override
    public Set<Long> mediumEdgeIds() {
        Set<Long> out = new HashSet<>();
        for (ObstacleReportDTO r : reportMapper.getReports(graphHolder.getRegionId())) {
            boolean live = ObstacleReportDTO.ST_PENDING.equals(r.getStatus())
                    || ObstacleReportDTO.ST_OPEN.equals(r.getStatus());
            if (live && ObstacleReportDTO.SEV_MEDIUM.equals(r.getSeverity())) {
                out.addAll(graphHolder.matchEdges(r.getLatitude(), r.getLongitude(), radiusOf(r)));
            }
        }
        return out;
    }

    /** 행에 값이 있으면 그것을, 없으면 전역 설정값을. {@code GraphHolder.radiusOf} 와 같은 규칙이다. */
    private double radiusOf(ObstacleReportDTO r) {
        return (r.getBlockRadiusM() == null) ? graphHolder.getDefaultBlockRadiusM() : r.getBlockRadiusM();
    }

    private void validate(ObstacleReportDTO dto) {
        if (dto.getUsername() == null || dto.getUsername().isBlank()) {
            throw new IllegalArgumentException("제보자가 없습니다. 로그인이 필요합니다.");
        }
        if (!SEVERITIES.contains(dto.getSeverity())) {
            throw new IllegalArgumentException("정도는 " + SEVERITIES + " 중 하나여야 합니다: " + dto.getSeverity());
        }
        if (!TYPES.contains(dto.getObstacleType())) {
            throw new IllegalArgumentException("유형은 " + TYPES + " 중 하나여야 합니다: " + dto.getObstacleType());
        }
        // DESCRIPTION 은 VARCHAR(500). 넘치면 DB 가 자르거나 터지므로 여기서 먼저 막는다.
        if (dto.getDescription() != null && dto.getDescription().length() > 500) {
            throw new IllegalArgumentException("설명은 500자를 넘을 수 없습니다.");
        }
        checkRadius(dto.getBlockRadiusM());
    }

    /**
     * 반경 상한 검사. <b>상한을 넘으면 잘라 담지 않고 거절한다.</b>
     *
     * <p>조용히 줄이면 화면에는 50m 를 넣었는데 실제로는 30m 만 막혀 있는 상태가 되고,
     * 사람은 그 차이를 알 방법이 없다. 공사구간에서 '반경을 키웠는데 왜 안 막히지' 로
     * 헤맨 적이 있어서, 값이 다르면 다르다고 말하는 쪽을 택했다.
     */
    private void checkRadius(Double radiusM) {
        if (radiusM == null) {
            return;   // 전역 설정값을 쓰겠다는 뜻이다
        }
        if (radiusM <= 0 || radiusM > maxRadiusM) {
            throw new IllegalArgumentException(
                    "차단 반경은 0 보다 크고 " + maxRadiusM + "m 이하여야 합니다: " + radiusM);
        }
    }
}

package kopo.poly.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 차단 사유가 되는 지점 1개. 공사구간과 제보(높음)를 같은 형태로 다루기 위한 것이다.
 *
 * <p>두 테이블 다 EDGES 와 FK 가 없고 <b>좌표 기반으로 매칭</b>한다는 점이 같다.
 * 공사구간 하나가 보행로 엣지 여러 개에 걸치기 때문에 EDGES 에 직접 기입하지 않기로 한 설계다.
 *
 * <p>{@code edgeId} 는 OBSTACLE_REPORTS 에만 있는 값이고, 채워져 있으면 좌표 매칭을 건너뛴다.
 * 그래프를 다시 빌드하면 EDGES.ID 가 바뀌므로 이 값을 못 믿을 때가 있는데, 그때는 NULL 로 두고 좌표로 다시 찾게 한다.
 */
@Getter
@Setter
public class BlockedPointDTO {

    /** 사람이 읽을 식별용. 공사명 또는 제보 ID. 로그에만 쓴다. */
    private String label;

    private double latitude;
    private double longitude;

    /** 제보에 기록된 EDGES.ID. NULL 이면 좌표로 매칭한다. */
    private Long edgeId;

    /**
     * 이 지점만의 차단 반경(m). {@code null} 이면 전역 설정 {@code wheelway.block-radius-m} 을 쓴다.
     *
     * <p>공사구간과 제보 <b>둘 다</b> 값이 있을 수 있는데, 조절하는 이유가 서로 다르다.
     *
     * <p>공사구간 — 지오코딩 좌표가 필지·교차로 중심에 찍히는 탓에 전역 반경 하나로는
     * <b>좌표가 먼 공사(반경이 커야 함)</b>와 <b>교차로에 찍힌 공사(반경이 작아야 함)</b>를
     * 동시에 만족시킬 수 없어서 둔 값이다. 즉 <b>좌표 오차를 덮는</b> 값이다.
     *
     * <p>제보 — 좌표는 사용자가 현장에서 직접 찍어 정확하다. 여기서 조절하는 것은 오차가 아니라
     * <b>장애물이 실제로 차지한 크기</b>다. 볼라드 하나와 보도 전체를 막은 자재 적치를
     * 같은 반경으로 다룰 수 없다. (2026-08-14 에 {@code OBSTACLE_REPORTS.BLOCK_RADIUS_M} 을 추가하면서
     * 바뀌었다. 그전에는 제보가 항상 전역값을 썼다.)
     */
    private Double blockRadiusM;
}

package kopo.poly.service;

import java.util.Set;

import kopo.poly.dto.RouteResultDTO;

/**
 * 경로 탐색.
 *
 * <p>이번 단계의 cost 는 {@code length_m} 하나뿐이다. 경사도·도로등급(소프트 스코어링)은
 * 하드필터와 탐색이 정상 동작하는 것을 확인한 뒤에 붙인다. 두 가지를 한꺼번에 넣으면
 * 하드필터 버그와 가중치 계산 버그를 구분할 수 없게 된다.
 */
public interface IRouteService {

    /** 차단 Set(공사기간 + 높음 제보)이 반영된 최단 경로. */
    RouteResultDTO searchRoute(double startLat, double startLng, double endLat, double endLng);

    /**
     * 일부 엣지를 추가로 제외하고 한 번 더 탐색한다.
     *
     * <p>'보통' 제보 처리에 쓸 자리다. "지나갈 수는 있지만 사용자가 선택"이 목적이라
     * K-shortest 같은 무거운 방식 대신 Dijkstra 를 2회 부르기로 했다.
     * <pre>
     *   경로A = searchRoute(...)                        // 제외 없음
     *   경로B = searchRoute(..., 보통제보엣지)            // 제외하고 한 번 더
     * </pre>
     *
     * @param extraBlockedEdgeIds 이번 탐색에서만 빼는 엣지. 차단 Set 자체는 건드리지 않는다
     */
    RouteResultDTO searchRoute(double startLat, double startLng, double endLat, double endLng,
                               Set<Long> extraBlockedEdgeIds);
}

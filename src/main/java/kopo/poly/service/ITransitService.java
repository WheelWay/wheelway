package kopo.poly.service;

import kopo.poly.dto.TransitResultDTO;

/**
 * 복합 경로 — <b>걸어서 → 버스 → 걸어서</b> 를 한 안내로 잇는다.
 *
 * <p><b>왜 버스 서비스와 따로 두는가</b>: 이 기능은 두 가지를 같이 알아야 한다.
 * 버스 쪽(어느 노선이 어디를 지나는가)과 길 쪽(휠체어로 거기까지 갈 수 있는가)이다.
 * {@code BusService} 에 넣으면 버스 서비스가 그래프를 알게 되고, {@code RouteService} 에
 * 넣으면 경로 탐색이 TAGO 를 알게 된다. 둘 다 원래 몰라도 되는 것이라,
 * <b>둘을 아는 자리를 하나 더 만들고 그 자리만 양쪽을 안다.</b>
 *
 * <p><b>도보 구간에만 휠체어 필터가 걸린다.</b> 계단은 그래프에 아예 없고,
 * 공사·제보 차단은 탐색 중에 걸러진다. 버스에 탄 구간은 필터랄 것이 없다 —
 * 대신 <b>저상 노선만</b> 쓰는 것이 그 자리의 필터다.
 */
public interface ITransitService {

    /**
     * 출발지에서 목적지까지 가는 안을 만든다.
     *
     * <p><b>버스 안이 없어도 빈손으로 돌아가지 않는다.</b> '도보만' 안은 길이 있는 한 늘 온다 —
     * 버스가 안 되는 것과 갈 수 없는 것은 전혀 다른 상황이고, 사용자가 알아야 할 것은
     * '그래서 어떻게 가나' 다.
     *
     * <p>계산이 가벼운 순서로 짠다. 후보를 직선거리로 먼저 추리고, <b>살아남은 몇 개에만</b>
     * 진짜 경로 탐색을 돌린다. 정류장 후보 5×5 에 전부 탐색을 돌리면 요청 하나가
     * Dijkstra 를 수십 번 부르게 된다.
     *
     * @param limit 안을 몇 개까지 줄지. 서너 개를 넘으면 고르는 일 자체가 일이 된다
     */
    TransitResultDTO plan(double startLat, double startLng,
                          double endLat, double endLng, int limit);
}

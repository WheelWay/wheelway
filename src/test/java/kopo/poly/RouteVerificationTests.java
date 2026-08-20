package kopo.poly;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import kopo.poly.dto.BlockedPointDTO;
import kopo.poly.dto.EdgeDTO;
import kopo.poly.dto.RouteResultDTO;
import kopo.poly.graph.BlockedEdges;
import kopo.poly.graph.GraphHolder;
import kopo.poly.graph.RouteGraph;
import kopo.poly.mapper.IGraphMapper;
import kopo.poly.service.IRouteService;

/**
 * 1단계 검증 — 하드필터와 그래프 탐색이 정상 동작하는지.
 *
 * <p>실제 DB(seoul-junggu 적재본)에 붙어서 돈다. 적재를 먼저 해야 한다:
 * {@code OsmGraphLoader load}
 *
 * <p>소프트 스코어링(경사도·도로등급)은 아직 없다. cost 는 {@code length_m} 하나뿐이다.
 */
@SpringBootTest
class RouteVerificationTests {

    /** 서울시청. 중구 한복판이라 그래프 최대 덩어리에 확실히 들어간다. */
    private static final double CITY_HALL_LAT = 37.5663;
    private static final double CITY_HALL_LNG = 126.9779;

    /** 명동성당. 시청에서 직선 약 850m. */
    private static final double MYEONGDONG_LAT = 37.5633;
    private static final double MYEONGDONG_LNG = 126.9873;

    @Autowired
    private GraphHolder graphHolder;

    @Autowired
    private BlockedEdges blockedEdges;

    @Autowired
    private IRouteService routeService;

    @Autowired
    private IGraphMapper graphMapper;

    @Test
    @DisplayName("1. 그래프가 계단을 뺀 상태로 올라온다")
    void 그래프_로드() {
        RouteGraph graph = graphHolder.getGraph();

        System.out.printf("노드 %d개, 엣지 %d개, 버려진 엣지 %d개%n",
                graph.nodeCount(), graph.edgeCount(), graph.droppedEdgeCount());

        // 적재 도구가 보고한 수치와 같아야 한다.
        // OSM 노드 23,769 + 분할 노드 8,989 (공사 60곳 반경 60m 안은 5m, 밖은 50m 간격).
        assertThat(graph.nodeCount()).isEqualTo(32_758);
        assertThat(graph.edgeCount()).isEqualTo(70_624);

        // 양 끝 노드를 못 찾아 버린 엣지가 있으면 적재가 중간에 끊긴 것이다.
        assertThat(graph.droppedEdgeCount()).isZero();
    }

    @Test
    @DisplayName("2. 계단 엣지는 인접리스트에 없다 — 하드필터 1")
    void 계단_제외() {
        RouteGraph graph = graphHolder.getGraph();

        // 그래프에 실제로 올라온 엣지 ID 전부
        Set<Long> loaded = new HashSet<>();
        for (int i = 0; i < graph.nodeCount(); i++) {
            for (RouteGraph.Edge e : graph.outgoing(i)) {
                loaded.add(e.edgeId());
            }
        }

        // EXCLUDE_REASON 조건 없이 전부 가져와, steps 인 것이 하나도 안 올라왔는지 본다.
        List<EdgeDTO> routable = graphMapper.getRoutableEdges(graphHolder.getRegionId());
        Set<Long> routableIds = new HashSet<>();
        routable.forEach(e -> routableIds.add(e.getId()));

        System.out.printf("탐색 대상 엣지 %d개, 그래프에 올라온 엣지 %d개%n", routableIds.size(), loaded.size());

        assertThat(loaded).isEqualTo(routableIds);
        assertThat(loaded).hasSize(70_624);
    }

    @Test
    @DisplayName("3. 시청 → 명동성당 최단경로가 나온다")
    void 최단경로() {
        RouteResultDTO res = routeService.searchRoute(
                CITY_HALL_LAT, CITY_HALL_LNG, MYEONGDONG_LAT, MYEONGDONG_LNG);

        System.out.printf("%s — %.1fm, 엣지 %d개, 방문 %d노드, %dms%n",
                res.getResultStatus(), res.getDistanceM(),
                res.getEdgeIds() == null ? 0 : res.getEdgeIds().size(),
                res.getVisitedNodes(), res.getDurationMs());
        System.out.printf("스냅 거리 출발 %.1fm / 도착 %.1fm%n", res.getStartSnapM(), res.getEndSnapM());

        assertThat(res.getResultStatus()).isEqualTo(RouteResultDTO.STATUS_SUCCESS);

        // 직선거리 약 850m. 실제 도로는 그보다 길고, 2배를 넘으면 이상하게 도는 것이다.
        assertThat(res.getDistanceM()).isBetween(850.0, 2_000.0);

        // 스냅이 멀면 엉뚱한 곳에서 출발한 것이다.
        assertThat(res.getStartSnapM()).isLessThan(100.0);
        assertThat(res.getEndSnapM()).isLessThan(100.0);

        // 좌표열이 끊기지 않는지 — 이웃한 점 사이가 비정상적으로 멀면 역추적이 잘못된 것이다.
        List<double[]> path = res.getPath();
        assertThat(path).hasSizeGreaterThan(2);
        for (int i = 0; i + 1 < path.size(); i++) {
            double gap = RouteGraph.haversineM(path.get(i)[0], path.get(i)[1],
                    path.get(i + 1)[0], path.get(i + 1)[1]);
            assertThat(gap).as("경로 %d번째 점 사이 간격", i).isLessThan(500.0);
        }
    }

    @Test
    @DisplayName("4. 차단한 엣지는 지나가지 않는다 — 하드필터 2·3")
    void 차단_우회() {
        RouteResultDTO before = routeService.searchRoute(
                CITY_HALL_LAT, CITY_HALL_LNG, MYEONGDONG_LAT, MYEONGDONG_LNG);
        assertThat(before.getResultStatus()).isEqualTo(RouteResultDTO.STATUS_SUCCESS);

        // 경로 한가운데 엣지를 막는다. 끝쪽을 막으면 출발지가 고립돼 '경로없음'이 나올 수 있다.
        long victim = before.getEdgeIds().get(before.getEdgeIds().size() / 2);

        try {
            blockedEdges.block(victim);

            RouteResultDTO after = routeService.searchRoute(
                    CITY_HALL_LAT, CITY_HALL_LNG, MYEONGDONG_LAT, MYEONGDONG_LNG);

            System.out.printf("차단 전 %.1fm → 차단 후 %s %.1fm (엣지 %d 차단)%n",
                    before.getDistanceM(), after.getResultStatus(), after.getDistanceM(), victim);

            assertThat(after.getResultStatus()).isEqualTo(RouteResultDTO.STATUS_SUCCESS);

            // 핵심 — 막은 엣지를 지나면 하드필터가 뚫린 것이다.
            assertThat(after.getEdgeIds()).doesNotContain(victim);

            // 최단경로를 하나 막았으니 거리는 같거나 늘어야 한다. 줄면 앞의 결과가 최단이 아니었다는 뜻이다.
            assertThat(after.getDistanceM()).isGreaterThanOrEqualTo(before.getDistanceM() - 0.01);

        } finally {
            blockedEdges.unblock(victim);
        }
    }

    @Test
    @DisplayName("5. 지역 범위 밖 좌표는 경로없음")
    void 범위_밖() {
        // 부산. 스냅 반경(300m) 안에 노드가 있을 리 없다.
        RouteResultDTO res = routeService.searchRoute(
                CITY_HALL_LAT, CITY_HALL_LNG, 35.1796, 129.0756);

        System.out.println(res.getResultStatus() + " — " + res.getMessage());

        assertThat(res.getResultStatus()).isEqualTo(RouteResultDTO.STATUS_NO_ROUTE);
    }

    @Test
    @DisplayName("6. 공사구간이 차단 Set 으로 구성된다 — 하드필터 2")
    void 공사구간_차단_반영() {
        List<BlockedPointDTO> zones = graphMapper.getActiveConstructionPoints(graphHolder.getRegionId());

        System.out.printf("공사구간 %d건 → 차단 엣지 %d개 (전체 %d개 중 %.1f%%)%n",
                zones.size(), blockedEdges.size(), graphHolder.getGraph().edgeCount(),
                100.0 * blockedEdges.size() / graphHolder.getGraph().edgeCount());

        assertThat(zones).isNotEmpty();
        assertThat(blockedEdges.size()).isPositive();

        // 경로 결과에 차단 엣지가 하나라도 섞이면 하드필터가 뚫린 것이다.
        RouteResultDTO res = routeService.searchRoute(
                CITY_HALL_LAT, CITY_HALL_LNG, MYEONGDONG_LAT, MYEONGDONG_LNG);
        assertThat(res.getResultStatus()).isEqualTo(RouteResultDTO.STATUS_SUCCESS);

        Set<Long> blocked = blockedEdges.snapshot();
        assertThat(res.getEdgeIds()).noneMatch(blocked::contains);
    }

    @Test
    @DisplayName("7. 진단 — 반경별로 몇 개가 막히는지 (block-radius-m 결정 근거)")
    void 반경별_차단량() {
        RouteGraph graph = graphHolder.getGraph();
        List<BlockedPointDTO> zones = graphMapper.getActiveConstructionPoints(graphHolder.getRegionId());

        System.out.println("공사구간 " + zones.size() + "건 기준");
        System.out.println(" 반경 | 차단엣지 | 비율   | 매칭실패 공사");

        for (double radius : new double[]{10, 15, 20, 30, 50}) {
            Set<Long> blocked = new HashSet<>();
            int unmatched = 0;

            for (BlockedPointDTO z : zones) {
                Set<Long> near = graph.edgeIdsNear(z.getLatitude(), z.getLongitude(), radius);
                if (near.isEmpty()) {
                    unmatched++;
                }
                blocked.addAll(near);
            }

            System.out.printf(" %3.0fm | %7d | %5.2f%% | %d건%n",
                    radius, blocked.size(), 100.0 * blocked.size() / graph.edgeCount(), unmatched);
        }

        // 값을 고르는 건 사람이 한다. 이 테스트는 근거를 뽑아 보여주는 것이 목적이다.
        assertThat(zones).isNotEmpty();
    }
}

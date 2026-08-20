package kopo.poly.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import kopo.poly.dto.RouteResultDTO;
import kopo.poly.graph.BlockedEdges;
import kopo.poly.graph.GraphHolder;
import kopo.poly.graph.RouteGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dijkstra 최단경로.
 *
 * <p>우선순위 큐 기반 표준 구현이다. 사방으로 균등하게 퍼지며 최적해를 보장한다.
 * A* 는 필수가 아니고, 이 검증이 끝나고 일정이 남으면 정렬 기준을
 * {@code 누적 cost} → {@code 누적 cost + 직선거리} 로 바꾸는 정도로 교체할 수 있다.
 * 직선거리는 항상 실제 도로거리보다 짧거나 같아서 admissible 하므로 최적해 보장은 유지된다.
 *
 * <h3>하드필터가 걸리는 두 지점</h3>
 * <ul>
 *   <li>계단 — 애초에 인접리스트에 없다. {@code EXCLUDE_REASON IS NULL} 로 조회했기 때문이다</li>
 *   <li>공사기간·높음제보 — 아래 {@code isBlocked()} 에서 O(1) 로 걸러진다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteService implements kopo.poly.service.IRouteService {

    private final GraphHolder graphHolder;
    private final BlockedEdges blockedEdges;

    /** 이 거리 안에 노드가 없으면 스냅 실패로 본다. */
    @Value("${wheelway.snap-max-m}")
    private double snapMaxM;

    /** 우선순위 큐 항목. cost 기준으로만 정렬한다. */
    private record Entry(int nodeIndex, double cost) {
    }

    @Override
    public RouteResultDTO searchRoute(double startLat, double startLng, double endLat, double endLng) {
        return searchRoute(startLat, startLng, endLat, endLng, Set.of());
    }

    @Override
    public RouteResultDTO searchRoute(double startLat, double startLng, double endLat, double endLng,
                                      Set<Long> extraBlockedEdgeIds) {

        long begin = System.currentTimeMillis();
        RouteResultDTO res = new RouteResultDTO();

        RouteGraph graph = graphHolder.getGraph();
        if (graph == null || graph.nodeCount() == 0) {
            res.setResultStatus(RouteResultDTO.STATUS_ERROR);
            res.setMessage("그래프가 비어 있습니다. region='" + graphHolder.getRegionId() + "' 적재를 확인하세요.");
            res.setDurationMs((int) (System.currentTimeMillis() - begin));
            return res;
        }

        // 1. 출발/도착 좌표를 가장 가까운 노드로 스냅
        int start = graph.snap(startLat, startLng, snapMaxM);
        int goal = graph.snap(endLat, endLng, snapMaxM);
        if (start < 0 || goal < 0) {
            res.setResultStatus(RouteResultDTO.STATUS_NO_ROUTE);
            res.setMessage("반경 " + (int) snapMaxM + "m 안에 그래프 노드가 없습니다. "
                    + (start < 0 ? "출발지" : "도착지") + "가 지역 범위 밖일 수 있습니다.");
            res.setDurationMs((int) (System.currentTimeMillis() - begin));
            return res;
        }

        // 가장 가까운 노드가 본 그래프에서 떨어져 나온 조각일 수 있다.
        // 명동성당 앞 pedestrian 구역처럼 큰길로 나가는 길이 계단뿐이면 그렇게 된다.
        // 그 경우 탐색해봐야 '경로없음'이 확정이라, 양쪽 다 최대 덩어리 안에서 다시 붙인다.
        if (graph.componentOf(start) != graph.componentOf(goal)) {
            int main = graph.largestComponentId();
            int reStart = graph.snapInComponent(startLat, startLng, snapMaxM, main);
            int reGoal = graph.snapInComponent(endLat, endLng, snapMaxM, main);

            if (reStart >= 0 && reGoal >= 0) {
                log.debug("서로 다른 덩어리({}, {})에 스냅되어 최대 덩어리로 다시 붙임",
                        graph.componentOf(start), graph.componentOf(goal));
                start = reStart;
                goal = reGoal;
            }
        }

        res.setStartNodeId(graph.nodeId(start));
        res.setEndNodeId(graph.nodeId(goal));
        res.setStartSnapM(RouteGraph.haversineM(startLat, startLng, graph.latitude(start), graph.longitude(start)));
        res.setEndSnapM(RouteGraph.haversineM(endLat, endLng, graph.latitude(goal), graph.longitude(goal)));

        // 2. Dijkstra
        //
        // cost 와 실제 거리를 따로 들고 간다. cost = 길이 × 도로종류 가중치 라서 둘이 다르다.
        // 탐색은 cost 를 최소화하고, 화면에 찍는 '총 거리'는 그렇게 고른 경로의 실제 미터다.
        // 이걸 안 나누면 "285m" 자리에 가중치가 섞인 의미 없는 숫자가 나온다.
        int n = graph.nodeCount();
        double[] dist = new double[n];
        double[] meters = new double[n];
        int[] prevNode = new int[n];
        long[] prevEdge = new long[n];
        String[] prevKind = new String[n];
        boolean[] settled = new boolean[n];

        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prevNode, -1);
        dist[start] = 0.0;
        meters[start] = 0.0;

        PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
        queue.add(new Entry(start, 0.0));

        int visited = 0;
        boolean reached = false;

        while (!queue.isEmpty()) {
            Entry cur = queue.poll();

            // 같은 노드가 더 나쁜 cost 로 큐에 남아 있을 수 있다. decrease-key 대신 쓰는 표준 방식이다.
            if (settled[cur.nodeIndex()]) {
                continue;
            }
            settled[cur.nodeIndex()] = true;
            visited++;

            if (cur.nodeIndex() == goal) {
                reached = true;
                break;
            }

            for (RouteGraph.Edge e : graph.outgoing(cur.nodeIndex())) {
                // 하드필터 — 공사기간 중 구간, 높음 제보. 그래프는 그대로 두고 여기서만 건너뛴다.
                if (isBlocked(e.edgeId(), extraBlockedEdgeIds)) {
                    continue;
                }
                if (settled[e.toIndex()]) {
                    continue;
                }

                double next = dist[cur.nodeIndex()] + e.costM();
                if (next < dist[e.toIndex()]) {
                    dist[e.toIndex()] = next;
                    meters[e.toIndex()] = meters[cur.nodeIndex()] + e.lengthM();
                    prevNode[e.toIndex()] = cur.nodeIndex();
                    prevEdge[e.toIndex()] = e.edgeId();
                    prevKind[e.toIndex()] = e.highway();
                    queue.add(new Entry(e.toIndex(), next));
                }
            }
        }

        res.setVisitedNodes(visited);
        res.setDurationMs((int) (System.currentTimeMillis() - begin));

        if (!reached) {
            res.setResultStatus(RouteResultDTO.STATUS_NO_ROUTE);
            res.setMessage("두 지점이 서로 다른 연결 덩어리에 있거나, 차단 때문에 우회로가 없습니다.");
            return res;
        }

        // 3. 역추적
        List<double[]> path = new ArrayList<>();
        List<Long> edgeIds = new ArrayList<>();
        List<String> edgeKinds = new ArrayList<>();
        for (int at = goal; at != -1; at = prevNode[at]) {
            path.add(new double[]{graph.latitude(at), graph.longitude(at)});
            if (at != start) {
                edgeIds.add(prevEdge[at]);
                edgeKinds.add(prevKind[at]);
            }
        }
        Collections.reverse(path);
        Collections.reverse(edgeIds);
        Collections.reverse(edgeKinds);


        res.setResultStatus(RouteResultDTO.STATUS_SUCCESS);
        res.setDistanceM(meters[goal]);      // 가중치 섞인 cost 가 아니라 실제 미터
        res.setPath(path);
        res.setEdgeIds(edgeIds);
        res.setEdgeKinds(edgeKinds);

        // cost 와 거리를 같이 남긴다. 가중치를 조정할 때 둘의 차이가 판단 근거가 된다.
        log.debug("경로 탐색 {}m (cost {}), 노드 {}개, 방문 {}개, {}ms",
                Math.round(meters[goal]), Math.round(dist[goal]), path.size(), visited, res.getDurationMs());

        return res;
    }

    /**
     * 이 엣지를 지나갈 수 없는가.
     *
     * <p>{@code extraBlocked} 는 '보통' 제보 제외 버전을 돌릴 때만 비어 있지 않다.
     * 차단 Set 자체는 건드리지 않으므로 다른 요청에 영향을 주지 않는다.
     */
    private boolean isBlocked(long edgeId, Set<Long> extraBlocked) {
        return blockedEdges.contains(edgeId) || extraBlocked.contains(edgeId);
    }
}

package kopo.poly.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kopo.poly.dto.EdgeDTO;
import kopo.poly.dto.NodeDTO;

/**
 * 도로 교차로와 <b>이미 그려져 있는</b> 보도를 잇는 연결 엣지를 만든다.
 *
 * <h3>왜 필요한가</h3>
 * 중구 대로 109.5km 중 55.3% 에는 평행한 보도가 OSM 에 이미 있다. 그런데
 * <b>도로망과 공유하는 노드가 1km 당 3.4개(약 290m 마다)뿐</b>이라, 보도가 바로 옆에 있어도
 * 들어갈 문이 없어 탐색이 도로 중심선을 탄다. 실제로 경로가 쓴 대로 구간의 66% 가
 * "평행 보도가 있는데도 중심선을 쓴" 경우였다.
 *
 * <p>그래서 <b>없는 보도를 만들지 않고, 있는 보도에 문만 달아준다.</b>
 *
 * <h3>교차로에서만 잇는 이유</h3>
 * 아무 데서나 이으면 무단횡단을 모델링하게 된다. 교차로(차수 3 이상)에서만 이으면
 * "횡단은 교차로에서" 라는 현실과 맞는다. 중간 지점은 잇지 않는다.
 *
 * <p>한 교차로에서 <b>사분면마다 가장 가까운 보도 노드 하나씩</b>만 잇는다.
 * 교차로의 네 모서리에 대응하며, 연결 수가 교차로당 4개를 넘지 않는다.
 *
 * <h3>하지 않는 것</h3>
 * 보도를 새로 그리지 않는다. 노드를 옮기지 않는다. 대로를 양쪽으로 쪼개지 않는다.
 * 쪼개려면 횡단보도 데이터가 필요한데 OSM 에 절반도 없다는 것을 측정으로 확인했다.
 *
 * <p><b>★ 사람이 직접 찍은 노드도 건드리지 않는다({@code keepOut}).</b>
 * 관리자가 없는 보도를 손으로 그리면 그 끝점이 '보도가 붙은 노드' 가 되는데,
 * 그러면 20m 안 도로 교차로가 그 점을 자동으로 물어간다. 관리자 눈에는
 * <b>잇지도 않은 도보와 차도가 저절로 이어진 것</b>으로 보인다.
 * 손으로 그린 자리는 사람이 이미 판단한 자리다 — 자동 연결이 끼어들 이유가 없다.
 */
public final class SidewalkConnector {

    /** 보도 계열로 볼 highway 값. */
    private static final Set<String> WALK = Set.of("footway", "pedestrian", "path", "living_street");

    /** 합성 엣지의 highway 값. 가중치 표에서 따로 조절할 수 있게 실제 태그와 다른 이름을 쓴다. */
    public static final String CONNECTOR = "connector";

    private SidewalkConnector() {
    }

    /**
     * @param maxM    교차로에서 이만큼 안에 있는 보도 노드만 잇는다
     * @param nextId  합성 엣지에 붙일 첫 ID. <b>음수</b>여야 실제 {@code EDGES.ID} 와 겹치지 않는다
     * @param keepOut 자동 연결에서 <b>제외</b>할 노드. 사람이 직접 찍은 것들이 여기 들어온다
     * @return 추가할 엣지들(양방향이라 항상 짝수 개)
     */
    public static List<EdgeDTO> build(List<NodeDTO> nodes, List<EdgeDTO> edges, double maxM, long nextId,
                                      Set<Long> keepOut) {
        Map<Long, NodeDTO> byId = new HashMap<>(nodes.size() * 2);
        for (NodeDTO n : nodes) {
            byId.put(n.getId(), n);
        }

        // 노드별 차수와, 그 노드에 보도/도로가 붙어 있는지
        Map<Long, int[]> degree = new HashMap<>();       // [차수, 보도붙음, 도로붙음]
        for (EdgeDTO e : edges) {
            boolean walk = e.getOsmHighway() != null && WALK.contains(e.getOsmHighway());
            int slot = walk ? 1 : 2;

            // 차수는 나가는 엣지로 센다. 방향별 2개라 모든 노드가 from 으로 한 번씩 나온다.
            int[] d = degree.computeIfAbsent(e.getFromNodeId(), k -> new int[3]);
            d[0]++;
            d[slot]++;

            // 반대쪽 노드에는 '무엇이 붙어 있는지'만 표시한다(차수는 그 노드 차례에서 센다)
            degree.computeIfAbsent(e.getToNodeId(), k -> new int[3])[slot]++;
        }

        // 이미 직접 이어져 있는 쌍은 다시 잇지 않는다
        Set<Long> linked = new HashSet<>();
        for (EdgeDTO e : edges) {
            linked.add(pairKey(e.getFromNodeId(), e.getToNodeId()));
        }

        // 보도 노드만 격자에 넣는다
        double cell = Math.max(20.0, maxM);
        Map<Long, List<Long>> grid = new HashMap<>();
        for (Map.Entry<Long, int[]> en : degree.entrySet()) {
            if (en.getValue()[1] == 0) {
                continue;                         // 보도가 안 붙은 노드
            }
            if (keepOut.contains(en.getKey())) {
                continue;                         // 사람이 직접 찍은 노드 - 자동으로 물어가지 않는다
            }
            NodeDTO n = byId.get(en.getKey());
            if (n != null) {
                grid.computeIfAbsent(cellKey(n.getLatitude(), n.getLongitude(), cell),
                        k -> new ArrayList<>()).add(en.getKey());
            }
        }

        List<EdgeDTO> out = new ArrayList<>();
        long id = nextId;

        for (Map.Entry<Long, int[]> en : degree.entrySet()) {
            int[] d = en.getValue();
            if (d[0] < 3 || d[2] == 0 || d[1] > 0) {
                continue;   // 교차로가 아니거나, 도로가 아니거나, 이미 보도가 붙어 있다
            }
            NodeDTO j = byId.get(en.getKey());
            if (j == null || keepOut.contains(en.getKey())) {
                continue;
            }

            // 사분면마다 가장 가까운 보도 노드 하나
            long[] best = {0, 0, 0, 0};
            double[] bestD = {maxM, maxM, maxM, maxM};

            for (long key : neighborCells(j.getLatitude(), j.getLongitude(), cell)) {
                for (long wid : grid.getOrDefault(key, List.of())) {
                    NodeDTO w = byId.get(wid);
                    if (w == null || linked.contains(pairKey(en.getKey(), wid))) {
                        continue;
                    }
                    double dist = RouteGraph.haversineM(j.getLatitude(), j.getLongitude(),
                            w.getLatitude(), w.getLongitude());
                    if (dist >= maxM || dist <= 0.5) {
                        continue;
                    }
                    int q = quadrant(w.getLatitude() - j.getLatitude(), w.getLongitude() - j.getLongitude());
                    if (dist < bestD[q]) {
                        bestD[q] = dist;
                        best[q] = wid;
                    }
                }
            }

            for (int q = 0; q < 4; q++) {
                if (best[q] == 0) {
                    continue;
                }
                out.add(edge(id--, en.getKey(), best[q], bestD[q]));
                out.add(edge(id--, best[q], en.getKey(), bestD[q]));
                linked.add(pairKey(en.getKey(), best[q]));
            }
        }
        return out;
    }

    private static EdgeDTO edge(long id, long from, long to, double lengthM) {
        EdgeDTO e = new EdgeDTO();
        e.setId(id);
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setLengthM(lengthM);
        e.setOsmHighway(CONNECTOR);
        return e;
    }

    private static int quadrant(double dLat, double dLon) {
        return (dLat >= 0 ? 0 : 2) + (dLon >= 0 ? 0 : 1);
    }

    /** 순서 무관 노드 쌍 키. 같은 두 노드면 방향이 달라도 같은 값이 나온다. */
    private static long pairKey(long a, long b) {
        long lo = Math.min(a, b), hi = Math.max(a, b);
        return lo * 1_000_003L + hi;
    }

    private static long cellKey(double lat, double lon, double cell) {
        long i = (long) Math.floor(lat * 111_320.0 / cell);
        long j = (long) Math.floor(lon * 88_000.0 / cell);
        return (i << 32) ^ (j & 0xffffffffL);
    }

    private static List<Long> neighborCells(double lat, double lon, double cell) {
        List<Long> out = new ArrayList<>(9);
        long i0 = (long) Math.floor(lat * 111_320.0 / cell);
        long j0 = (long) Math.floor(lon * 88_000.0 / cell);
        for (long i = i0 - 1; i <= i0 + 1; i++) {
            for (long j = j0 - 1; j <= j0 + 1; j++) {
                out.add((i << 32) ^ (j & 0xffffffffL));
            }
        }
        return out;
    }
}

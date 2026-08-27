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
 * 인메모리 인접리스트 그래프. 서버 기동 시 1회 만들고 그 뒤로는 <b>읽기 전용</b>이다.
 *
 * <p>엣지 차단은 이 그래프를 건드리지 않는다. 차단은 {@link BlockedEdges} 가 들고 있는 별도 Set 이고,
 * 탐색 중에 O(1) 로 확인해서 건너뛴다. 제보 하나 들어올 때마다 그래프를 다시 만들지 않으려는 설계다.
 *
 * <h3>노드를 int 인덱스로 바꿔 쓰는 이유</h3>
 * DB 의 {@code NODES.ID}(long)를 그대로 쓰면 Dijkstra 의 거리표가 {@code HashMap<Long, Double>} 이 되는데,
 * 0..N-1 로 다시 번호를 매기면 {@code double[]} 하나로 끝난다. 코드도 짧아지고 박싱도 사라진다.
 * 바깥(컨트롤러·로그)과 주고받을 때만 {@link #nodeId(int)} 로 되돌린다.
 *
 * <h3>거리 계산</h3>
 * 엣지 길이는 적재 시점에 하버사인으로 계산해 {@code EDGES.LENGTH_M} 에 넣어뒀으므로 여기서 다시 재지 않는다.
 * 여기서 계산하는 건 스냅·좌표매칭용 거리뿐이다.
 */
public final class RouteGraph {

    /** 지구 평균 반지름(m). 적재 도구({@code OsmGraphLoader})와 같은 값을 쓴다. */
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    /**
     * 방향이 있는 엣지 1개. {@code edgeId} 는 차단 Set 과 대조할 EDGES.ID 다.
     *
     * <p>한때 공사 좌표를 <b>붙일 때</b> 보도 계열을 우선하도록 했다가 되돌렸다.
     * 원본에 '도로 어느 쪽 보도인지'가 없어서, 좌표가 차도 위에 찍혔을 때 가까운 보도 대신
     * <b>반대편 보도</b>를 고르는 일이 생겼다. 좌표만으로는 판별할 수 없다.
     */
    /**
     * @param lengthM 실제 거리(m). 화면에 찍는 '총 거리'는 이것의 합이다
     * @param costM   탐색이 최소화하는 값 = {@code lengthM × 도로종류 가중치}.
     *                가중치가 전부 1.0 이면 {@code lengthM} 과 같아진다
     * @param highway 원본 {@code OSM_HIGHWAY} 값. 탐색에는 쓰지 않고
     *                <b>결과를 설명하는 데만</b> 쓴다 — 화면이 보도/차도를 구분해 그리려면
     *                엣지마다 이 값이 필요하다. 보도 연결은 {@code connector},
     *                수동 엣지는 {@code crossing} 이 들어온다
     */
    public record Edge(long edgeId, int toIndex, double lengthM, double costM, String highway) {
    }

    private final String regionId;

    /** 인덱스 → NODES.ID */
    private final long[] nodeIds;
    private final double[] lats;
    private final double[] lons;

    /** NODES.ID → 인덱스 */
    private final Map<Long, Integer> indexOf;

    /** 인덱스 → 그 노드에서 나가는 엣지들 */
    private final Edge[][] adjacency;

    private final int edgeCount;

    /** 양 끝 노드 중 하나가 NODES 에 없어서 버린 엣지 수. 정상 적재라면 0 이다. */
    private final int droppedEdgeCount;

    /** 인덱스 → 연결 덩어리 번호. 두 노드의 값이 다르면 서로 오갈 수 없다. */
    private final int[] componentOf;

    /** 가장 큰 덩어리의 번호. 스냅이 실패했을 때 되돌아갈 기준점이다. */
    private final int largestComponentId;

    /** 덩어리 크기(큰 것부터). */
    private final List<Integer> componentSizes;

    /** 인덱스 → 보도 엣지가 붙은 노드인가. 스냅이 차도 중심선을 물지 않게 하는 데 쓴다. */
    private final boolean[] walkNode;

    /**
     * 보도 노드를 이만큼 더 멀어도 먼저 잡는다(m). 0 이면 예전처럼 무조건 최단이다.
     *
     * <p>너무 키우면 <b>길 건너편 보도</b>를 물어 무단횡단을 시킨다. 왕복 4차선의
     * 반대편 보도가 대략 30m 이므로 그보다 작아야 한다.
     */
    private final double preferWalkM;

    private RouteGraph(String regionId, long[] nodeIds, double[] lats, double[] lons,
                       Map<Long, Integer> indexOf, Edge[][] adjacency, int edgeCount, int droppedEdgeCount,
                       double preferWalkM) {
        this.regionId = regionId;
        this.nodeIds = nodeIds;
        this.lats = lats;
        this.lons = lons;
        this.indexOf = indexOf;
        this.adjacency = adjacency;
        this.edgeCount = edgeCount;
        this.droppedEdgeCount = droppedEdgeCount;

        this.walkNode = markWalkNodes(adjacency);
        this.preferWalkM = Math.max(0.0, preferWalkM);

        this.componentOf = new int[nodeIds.length];
        this.componentSizes = new ArrayList<>();
        this.largestComponentId = findComponents();
    }

    /**
     * 조회 결과로 그래프를 만든다.
     *
     * @param edges  {@code EXCLUDE_REASON IS NULL} 로 이미 걸러진 것이어야 한다(계단 하드필터).
     * @param weight OSM {@code highway} 값 → 가중치. 보도 1.0, 차도는 그보다 크게 준다.
     *               <b>1.0 미만을 주지 말 것</b> — 나중에 A* 로 바꿀 때 직선거리 휴리스틱이
     *               admissible 하지 않게 되어 최적해 보장이 깨진다
     */
    public static RouteGraph build(String regionId, List<NodeDTO> nodes, List<EdgeDTO> edges,
                                   java.util.function.ToDoubleFunction<String> weight,
                                   double preferWalkM) {
        int n = nodes.size();
        long[] nodeIds = new long[n];
        double[] lats = new double[n];
        double[] lons = new double[n];
        Map<Long, Integer> indexOf = new HashMap<>(Math.max(16, n * 2));

        for (int i = 0; i < n; i++) {
            NodeDTO node = nodes.get(i);
            nodeIds[i] = node.getId();
            lats[i] = node.getLatitude();
            lons[i] = node.getLongitude();
            indexOf.put(node.getId(), i);
        }

        // 먼저 노드별 차수를 세어 배열 크기를 정확히 잡는다. List 를 거치지 않으므로 재할당이 없다.
        int[] degree = new int[n];
        int usable = 0;
        for (EdgeDTO e : edges) {
            Integer from = indexOf.get(e.getFromNodeId());
            Integer to = indexOf.get(e.getToNodeId());
            if (from == null || to == null) {
                continue;
            }
            degree[from]++;
            usable++;
        }

        Edge[][] adjacency = new Edge[n][];
        for (int i = 0; i < n; i++) {
            adjacency[i] = new Edge[degree[i]];
        }

        int[] fill = new int[n];
        for (EdgeDTO e : edges) {
            Integer from = indexOf.get(e.getFromNodeId());
            Integer to = indexOf.get(e.getToNodeId());
            if (from == null || to == null) {
                continue;
            }
            double w = Math.max(1.0, weight.applyAsDouble(e.getOsmHighway()));
            adjacency[from][fill[from]++] =
                    new Edge(e.getId(), to, e.getLengthM(), e.getLengthM() * w, e.getOsmHighway());
        }

        return new RouteGraph(regionId, nodeIds, lats, lons, indexOf, adjacency,
                usable, edges.size() - usable, preferWalkM);
    }

    /**
     * 보도로 볼 {@code highway} 값.
     *
     * <p>가중치 표와 따로 두는 이유: 저쪽은 '걷기 얼마나 힘든가' 이고 이쪽은
     * <b>'차도 한복판인가 아닌가'</b> 다. {@code primary} 는 휠체어에 편해서 가중치가
     * 1.3 으로 낮지만 그 노드는 <b>왕복 4차선 중심선</b>이다. 거기에 출발·도착을
     * 붙이면 안 된다.
     *
     * <p>{@code connector} 는 {@link SidewalkConnector} 가 차도 교차로와 보도를 이으려고
     * 만든 엣지다. 그 끝은 보도 쪽이므로 보도로 친다.
     */
    private static final Set<String> WALK_HIGHWAYS =
            Set.of("footway", "pedestrian", "path", "crossing", "connector", "living_street");

    /** 보도 엣지가 붙은 노드 표시. 엣지의 highway 로 판단한다(따로 저장하는 값이 아니다). */
    private static boolean[] markWalkNodes(Edge[][] adjacency) {
        boolean[] out = new boolean[adjacency.length];
        for (int i = 0; i < adjacency.length; i++) {
            for (Edge e : adjacency[i]) {
                if (e.highway() != null && WALK_HIGHWAYS.contains(e.highway())) {
                    out[i] = true;
                    out[e.toIndex()] = true;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 조회

    public String regionId() {
        return regionId;
    }

    /**
     * 안내 가능한 지역의 경계. {@code [남西위도, 남西경도, 북東위도, 북東경도]}.
     *
     * <p>화면이 "여기는 아직 안내하지 않습니다"를 판단하는 데 쓴다. 현재위치를 잡아
     * 지도를 옮겼는데 그곳에 데이터가 없으면, 사용자는 서비스가 고장 난 줄 안다.
     * 지역을 바꾸면(region-id) 이 값도 따라 바뀌므로 화면에 박아둘 수 없다.
     *
     * <p>노드가 없으면 {@code null} 을 준다.
     */
    public double[] bounds() {
        if (lats.length == 0) {
            return null;
        }
        double minLat = lats[0], maxLat = lats[0], minLon = lons[0], maxLon = lons[0];
        for (int i = 1; i < lats.length; i++) {
            if (lats[i] < minLat) minLat = lats[i];
            if (lats[i] > maxLat) maxLat = lats[i];
            if (lons[i] < minLon) minLon = lons[i];
            if (lons[i] > maxLon) maxLon = lons[i];
        }
        return new double[] { minLat, minLon, maxLat, maxLon };
    }

    public int nodeCount() {
        return nodeIds.length;
    }

    public int edgeCount() {
        return edgeCount;
    }

    public int droppedEdgeCount() {
        return droppedEdgeCount;
    }

    public long nodeId(int index) {
        return nodeIds[index];
    }

    public double latitude(int index) {
        return lats[index];
    }

    public double longitude(int index) {
        return lons[index];
    }

    /** @return 인덱스. 없으면 -1 */
    public int indexOf(long nodeId) {
        Integer i = indexOf.get(nodeId);
        return (i == null) ? -1 : i;
    }

    public Edge[] outgoing(int index) {
        return adjacency[index];
    }

    // ------------------------------------------------------------------ 스냅

    /**
     * 좌표를 가장 가까운 노드로 스냅한다. 나가는 엣지가 하나도 없는 노드는 건너뛴다
     * (계단으로만 이어져 있던 노드가 하드필터 후 이렇게 남는다. 붙어봐야 아무 데도 못 간다).
     *
     * <p>노드 2만여 개를 전부 훑는 단순 방식이다. 한 번에 1ms 안쪽이라 이번 단계에서는 문제가 없고,
     * 느려지면 격자 인덱스를 얹으면 된다. 지금 공간 인덱스를 넣으면 검증할 것만 늘어난다.
     *
     * @param maxDistanceM 이 거리를 넘으면 스냅 실패로 본다
     * @return 노드 인덱스. 반경 안에 아무것도 없으면 -1
     */
    public int snap(double lat, double lon, double maxDistanceM) {
        return snapInComponent(lat, lon, maxDistanceM, ANY_COMPONENT);
    }

    /** {@link #snapInComponent} 에 넘기면 덩어리를 가리지 않는다. */
    public static final int ANY_COMPONENT = -1;

    /**
     * 지정한 연결 덩어리 안에서만 가장 가까운 노드를 찾는다.
     *
     * <p>이게 필요한 이유는 실제 데이터에서 확인됐다. 명동성당 앞 {@code pedestrian} 구역은
     * 큰길로 나가는 길이 <b>계단뿐</b>이라, 계단을 빼는 순간 별도 조각이 된다.
     * 그냥 가장 가까운 노드로 붙이면 22m 앞의 그 조각에 붙어서 어디로도 갈 수 없게 된다.
     *
     * @param componentId {@link #ANY_COMPONENT} 면 덩어리를 가리지 않는다
     */
    public int snapInComponent(double lat, double lon, double maxDistanceM, int componentId) {
        int best = -1;
        double bestScore = maxDistanceM + preferWalkM;

        for (int i = 0; i < nodeIds.length; i++) {
            if (adjacency[i].length == 0) {
                continue;
            }
            if (componentId != ANY_COMPONENT && componentOf[i] != componentId) {
                continue;
            }
            double d = haversineM(lat, lon, lats[i], lons[i]);
            if (d > maxDistanceM) {
                continue;
            }
            /*
              ★ 가장 가까운 노드가 아니라 <b>보도를 우대한 거리</b>로 고른다.

              실측(청주 푸르지오캐슬 · 2026-08-26): 정류장으로 가는 도보 경로의 끝점이
              <b>차도선 위 0.0m</b> 였고 보도는 13~15m 옆에 있었다. 보도 노드는
              20~50m 간격인데 차도 중심선 노드는 촘촘해서 늘 중심선이 이긴다.
              그래서 바로 옆에 보도가 그려져 있는데도 경로가 차도에서 끝난다.

              차도 노드에만 preferWalkM 벌점을 준다. 보도가 그 차이 안에 있으면 보도가
              이기고, 보도가 아예 없는 곳에서는 벌점이 모두에게 같아 예전과 똑같다.
            */
            double score = walkNode[i] ? d : d + preferWalkM;
            if (score <= bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    // -------------------------------------------------------------- 좌표 매칭

    /**
     * 한 지점 주변의 엣지 ID 를 모은다. 공사구간·제보 좌표를 차단 엣지로 바꾸는 데 쓴다.
     *
     * <p>같은 구간의 두 방향 엣지가 둘 다 반경 안에 들어오므로 <b>양방향이 함께 차단된다</b>.
     * 공사로 막힌 보도를 한쪽 방향으로만 못 지나가게 하는 건 말이 안 되니 이게 맞는 동작이다.
     *
     * <p>점과 선분 사이 거리로 판정한다. 엣지 끝점만 보면 긴 엣지의 한가운데에 있는 공사를 놓친다.
     *
     * @param radiusM 이 반경 안의 엣지를 차단한다. 좁으면 놓치고 넓으면 멀쩡한 골목까지 막는다
     */
    public Set<Long> edgeIdsNear(double lat, double lon, double radiusM) {
        Set<Long> found = new HashSet<>();

        for (int from = 0; from < adjacency.length; from++) {
            for (Edge e : adjacency[from]) {
                double d = pointToSegmentM(lat, lon,
                        lats[from], lons[from], lats[e.toIndex()], lons[e.toIndex()]);
                if (d <= radiusM) {
                    found.add(e.edgeId());
                }
            }
        }
        return found;
    }

    /**
     * 주어진 엣지 ID 들의 좌표열을 모은다. 차단 구간을 지도에 그리는 데 쓴다.
     *
     * <p>인접리스트를 한 번 훑는다. 엣지 ID 로 바로 찾는 색인은 두지 않았다 —
     * 이 기능은 화면 로드 때 한 번 부르는 것이라 5만 개를 훑어도 수 ms 다.
     *
     * @return {@code [[[위도,경도],[위도,경도]], ...]} — 선분 하나가 배열 하나
     */
    public List<double[][]> geometryOf(Set<Long> edgeIds) {
        List<double[][]> out = new ArrayList<>(edgeIds.size());

        for (int from = 0; from < adjacency.length; from++) {
            for (Edge e : adjacency[from]) {
                if (edgeIds.contains(e.edgeId())) {
                    out.add(new double[][]{
                            {lats[from], lons[from]},
                            {lats[e.toIndex()], lons[e.toIndex()]}
                    });
                }
            }
        }
        return out;
    }

    /**
     * 한 지점 주변 엣지의 <b>좌표열</b>을 모은다. {@link #edgeIdsNear} 와 판정은 같고 결과만 다르다.
     *
     * <p>공사 지점 하나가 실제로 어느 보도를 얼마나 막고 있는지 지도에 <b>선으로</b> 그리는 데 쓴다.
     * 크롤링 원문의 '공사구간'은 {@code A ~ B} 형식이지만 실제로는 A 와 B 가 같은 주소라
     * 원본만으로는 이을 두 점이 없다. 대신 차단되는 엣지를 그리면 범위가 그대로 드러난다.
     *
     * <p>같은 구간의 두 방향 엣지가 둘 다 걸리므로 좌표열이 겹쳐 그려진다.
     * 화면에 선을 얹는 용도라 문제되지 않는다.
     *
     * @return {@code [[[위도,경도],[위도,경도]], ...]}
     */
    public List<double[][]> segmentsNear(double lat, double lon, double radiusM) {
        List<double[][]> out = new ArrayList<>();

        for (int from = 0; from < adjacency.length; from++) {
            for (Edge e : adjacency[from]) {
                // 방향이 반대인 중복은 한 번만 그린다.
                if (from > e.toIndex()) {
                    continue;
                }
                double d = pointToSegmentM(lat, lon,
                        lats[from], lons[from], lats[e.toIndex()], lons[e.toIndex()]);
                if (d <= radiusM) {
                    out.add(new double[][]{
                            {lats[from], lons[from]},
                            {lats[e.toIndex()], lons[e.toIndex()]}
                    });
                }
            }
        }
        return out;
    }

    /**
     * 반경 안에 아무것도 없을 때 쓰는 <b>차선책</b> — 가장 가까운 구간 하나를 찾는다.
     *
     * <p>지오코딩 좌표가 필지 중심에 찍혀 실제 보도와 멀어지면 반경 매칭이 0건이 되는데,
     * 그렇다고 그 공사를 없는 셈 치면 안 된다. 공사가 있다고 기록된 이상 무언가는 막혀야 한다.
     * 그래서 반경을 벗어나더라도 가장 가까운 보도 한 구간을 막는다.
     *
     * <p>같은 구간의 두 방향이 같은 거리로 잡히므로 양방향이 함께 막힌다.
     *
     * @param maxDistanceM 이 거리마저 넘으면 포기한다. 그래프에서 너무 먼 좌표를 억지로 붙이지 않기 위한 상한
     */
    public Set<Long> edgeIdsNearest(double lat, double lon, double maxDistanceM) {
        double best = nearestDistanceM(lat, lon, maxDistanceM);
        return (best < 0) ? Set.of() : edgeIdsNear(lat, lon, best + 0.01);
    }

    /** {@link #edgeIdsNearest} 와 같은 판정으로 좌표열을 돌려준다. 화면에 그리는 용도다. */
    public List<double[][]> segmentsNearest(double lat, double lon, double maxDistanceM) {
        double best = nearestDistanceM(lat, lon, maxDistanceM);
        return (best < 0) ? List.of() : segmentsNear(lat, lon, best + 0.01);
    }

    /**
     * 이 좌표에서 가장 가까운 보도까지의 거리(m). 상한을 넘으면 -1.
     *
     * <p>지오코딩 결과가 얼마나 쓸만한지 재는 지표다. 값이 작을수록 실제 길 위에 찍혔다는 뜻이고,
     * 크면 필지 중심이나 건물 안에 찍혔다는 뜻이라 손을 봐야 한다.
     */
    public double distanceToNearestEdgeM(double lat, double lon, double maxDistanceM) {
        return nearestDistanceM(lat, lon, maxDistanceM);
    }

    /** @return 가장 가까운 엣지까지의 거리(m). 상한을 넘으면 -1 */
    private double nearestDistanceM(double lat, double lon, double maxDistanceM) {
        double best = maxDistanceM;
        boolean found = false;

        for (int from = 0; from < adjacency.length; from++) {
            for (Edge e : adjacency[from]) {
                double d = pointToSegmentM(lat, lon,
                        lats[from], lons[from], lats[e.toIndex()], lons[e.toIndex()]);
                if (d <= best) {
                    best = d;
                    found = true;
                }
            }
        }
        return found ? best : -1;
    }

    // ------------------------------------------------------------------ 계산

    /** 하버사인 거리(m). */
    public static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double r1 = Math.toRadians(lat1);
        double r2 = Math.toRadians(lat2);
        double dLat = r2 - r1;
        double dLon = Math.toRadians(lon2 - lon1);

        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(r1) * Math.cos(r2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /**
     * 점 P 와 선분 AB 사이의 최단거리(m).
     *
     * <p>구면 계산 대신 P 를 원점으로 한 평면 근사를 쓴다. 수백 m 규모에서 오차가 cm 수준이라
     * 차단 반경 판정에는 충분하고, 삼각함수 호출이 줄어 5만 엣지를 훑어도 부담이 없다.
     */
    private static double pointToSegmentM(double pLat, double pLon,
                                          double aLat, double aLon,
                                          double bLat, double bLon) {
        // 위도 1도의 길이는 어디서나 거의 같고, 경도 1도는 cos(위도) 만큼 짧아진다.
        double mPerDegLat = Math.PI * EARTH_RADIUS_M / 180.0;
        double mPerDegLon = mPerDegLat * Math.cos(Math.toRadians(pLat));

        double ax = (aLon - pLon) * mPerDegLon;
        double ay = (aLat - pLat) * mPerDegLat;
        double bx = (bLon - pLon) * mPerDegLon;
        double by = (bLat - pLat) * mPerDegLat;

        double dx = bx - ax;
        double dy = by - ay;
        double lenSq = dx * dx + dy * dy;

        // 길이 0 인 선분(적재 단계에서 걸렀지만 방어). 그냥 한쪽 끝점까지의 거리다.
        if (lenSq == 0.0) {
            return Math.sqrt(ax * ax + ay * ay);
        }

        // P(원점)를 AB 에 정사영한 위치. 선분 밖으로 나가면 끝점으로 자른다.
        double t = -(ax * dx + ay * dy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));

        double cx = ax + t * dx;
        double cy = ay + t * dy;
        return Math.sqrt(cx * cx + cy * cy);
    }

    // ------------------------------------------------------------------ 진단

    /**
     * 연결 덩어리를 나눈다. 생성자에서 1회 실행된다.
     *
     * <p>중구 bbox 원본은 계단을 빼면 최대 덩어리가 90% 수준이고 나머지는 파편이다.
     * 즉 <b>출발지와 도착지가 서로 다른 덩어리면 정상 동작인데도 '경로없음'이 나온다.</b>
     * 미리 나눠두면 스냅 단계에서 그 상황을 피할 수 있고, 기동 로그로 상황도 알 수 있다.
     *
     * @return 가장 큰 덩어리의 번호
     */
    private int findComponents() {
        java.util.Arrays.fill(componentOf, -1);
        int[] stack = new int[nodeIds.length];
        int largestId = -1;
        int largestSize = 0;
        int id = 0;

        // 방향이 있는 그래프지만 엣지를 방향별로 2개씩 만들었으므로 도달성은 무방향과 같다.
        for (int start = 0; start < nodeIds.length; start++) {
            if (componentOf[start] != -1) {
                continue;
            }
            int top = 0;
            stack[top++] = start;
            componentOf[start] = id;
            int size = 0;

            while (top > 0) {
                int cur = stack[--top];
                size++;
                for (Edge e : adjacency[cur]) {
                    if (componentOf[e.toIndex()] == -1) {
                        componentOf[e.toIndex()] = id;
                        stack[top++] = e.toIndex();
                    }
                }
            }

            componentSizes.add(size);
            if (size > largestSize) {
                largestSize = size;
                largestId = id;
            }
            id++;
        }

        componentSizes.sort((a, b) -> Integer.compare(b, a));
        return largestId;
    }

    /** 두 노드의 값이 다르면 서로 오갈 수 없다. */
    public int componentOf(int index) {
        return componentOf[index];
    }

    public int largestComponentId() {
        return largestComponentId;
    }

    /** 덩어리 크기, 큰 것부터. */
    public List<Integer> componentSizes() {
        return componentSizes;
    }

    /**
     * 나가는 엣지가 있는 노드 수. 전체 노드 수와의 차이가 곧 '계단으로만 이어져 있던 노드' 수다.
     */
    public int connectedNodeCount() {
        int count = 0;
        for (Edge[] out : adjacency) {
            if (out.length > 0) {
                count++;
            }
        }
        return count;
    }
}

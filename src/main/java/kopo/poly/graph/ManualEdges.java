package kopo.poly.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kopo.poly.dto.EdgeDTO;
import kopo.poly.dto.ManualEdgeDTO;
import kopo.poly.dto.NodeDTO;

/**
 * {@code MANUAL_EDGES} 를 메모리 그래프에 반영한다. <b>DB 의 EDGES 는 건드리지 않는다.</b>
 *
 * <h3>왜 좌표로 다시 찾는가</h3>
 * {@code EDGES.ID} 는 재적재({@code OsmGraphLoader load --force})하면 새로 매겨진다.
 * ID 로 저장해두면 그다음 기동에서 <b>엉뚱한 엣지</b>를 지우거나 잇게 된다.
 * 그래서 양 끝 좌표만 남기고 기동할 때마다 가장 가까운 노드·엣지를 다시 찾는다.
 *
 * <h3>적용 순서</h3>
 * 삭제 → 추가 순이다. 사람이 "이 엣지를 지우고 대신 저기로 잇겠다"고 할 때
 * 순서가 반대면 방금 이은 것을 지울 수 있다.
 */
public final class ManualEdges {

    /** 추가 시 {@code EDGE_KIND} 가 비어 있으면 쓸 값. 가중치 표에 이 키가 있어야 한다. */
    public static final String DEFAULT_KIND = "crossing";

    private ManualEdges() {
    }

    /**
     * 스냅·매칭 결과. 화면과 로그에 무엇이 반영되고 무엇이 실패했는지 알려주려고 센다.
     *
     * @param splitNodes 붙일 노드가 없어 엣지를 쪼개 만든 노드 수
     */
    public record Result(int removed, int added, int failed, int splitNodes, List<String> problems,
                         Set<Long> addedEdgeIds, Set<Long> addedNodeIds) {

        public Result(int removed, int added, int failed, int splitNodes, List<String> problems) {
            this(removed, added, failed, splitNodes, problems, Set.of(), Set.of());
        }
    }

    /**
     * @param snapMaxM  찍은 좌표를 <b>기존 노드</b>에 붙일 최대 거리
     * @param edgeMaxM  노드가 없을 때 <b>엣지를 쪼개서</b> 노드를 만들 최대 거리.
     *                  보도 한가운데에 횡단보도를 다는 경우가 이것이다
     * @param matchMaxM 삭제 대상 엣지의 양 끝이 이 거리 안에 들어와야 같은 엣지로 본다
     * @param nextId    합성 엣지·노드에 붙일 첫 ID. <b>음수</b>여야 실제 ID 와 겹치지 않는다
     */
    public static Result apply(List<NodeDTO> nodes, List<EdgeDTO> edges, List<ManualEdgeDTO> manual,
                               double snapMaxM, double edgeMaxM, double matchMaxM, long nextId) {

        Map<Long, NodeDTO> byId = new HashMap<>(nodes.size() * 2);
        for (NodeDTO n : nodes) {
            byId.put(n.getId(), n);
        }

        List<String> problems = new ArrayList<>();
        int removed = 0, added = 0, failed = 0;

        // ---- 1) 삭제 : 양 끝 좌표가 모두 가까운 엣지를 뺀다(양방향 다) ----
        for (ManualEdgeDTO m : manual) {
            if (!ManualEdgeDTO.REMOVE.equals(m.getAction())) {
                continue;
            }
            int hit = 0;
            for (Iterator<EdgeDTO> it = edges.iterator(); it.hasNext(); ) {
                EdgeDTO e = it.next();
                NodeDTO a = byId.get(e.getFromNodeId()), b = byId.get(e.getToNodeId());
                if (a == null || b == null) {
                    continue;
                }
                if (samePair(m, a, b, matchMaxM)) {
                    it.remove();
                    hit++;
                }
            }
            if (hit == 0) {
                failed++;
                problems.add("삭제 #" + m.getId() + " 대상 엣지를 못 찾음 ("
                        + matchMaxM + "m 안에 일치하는 엣지 없음)");
            } else {
                removed += hit;
            }
        }

        // ---- 2) 추가 : 양 끝에 붙을 노드를 정하고(없으면 엣지를 쪼개 만들고) 잇는다 ----
        long[] id = {nextId};
        int splits = 0;
        // 사람이 만든 것만 따로 모은다. 화면이 '수정 모드일 때만' 보여주려면 구분이 필요하다.
        Set<Long> addedEdgeIds = new HashSet<>();
        Set<Long> addedNodeIds = new HashSet<>();
        for (ManualEdgeDTO m : manual) {
            boolean exact = ManualEdgeDTO.ADD_EXACT.equals(m.getAction());
            if (!exact && !ManualEdgeDTO.ADD.equals(m.getAction())) {
                continue;
            }
            int before = nodes.size();
            NodeDTO a = exact
                    ? freeNode(nodes, byId, m.getFromLat(), m.getFromLng(), id)
                    : endpoint(nodes, edges, byId, m.getFromLat(), m.getFromLng(), snapMaxM, edgeMaxM, id);
            NodeDTO b = exact
                    ? freeNode(nodes, byId, m.getToLat(), m.getToLng(), id)
                    : endpoint(nodes, edges, byId, m.getToLat(), m.getToLng(), snapMaxM, edgeMaxM, id);
            splits += nodes.size() - before;
            for (int k = before; k < nodes.size(); k++) {
                addedNodeIds.add(nodes.get(k).getId());
            }

            if (a == null || b == null) {
                failed++;
                problems.add("추가 #" + m.getId() + " 붙일 곳을 못 찾음 ("
                        + (int) snapMaxM + "m 안에 노드도, " + (int) edgeMaxM + "m 안에 엣지도 없음)");
                continue;
            }
            if (a.getId() == b.getId()) {
                failed++;
                problems.add("추가 #" + m.getId() + " 양 끝이 같은 노드에 붙음 — 두 점을 더 벌려 찍을 것");
                continue;
            }

            String kind = (m.getEdgeKind() == null || m.getEdgeKind().isBlank())
                    ? DEFAULT_KIND : m.getEdgeKind();
            double len = RouteGraph.haversineM(a.getLatitude(), a.getLongitude(),
                    b.getLatitude(), b.getLongitude());

            long fwd = id[0]--, rev = id[0]--;
            edges.add(edge(fwd, a.getId(), b.getId(), len, kind));
            edges.add(edge(rev, b.getId(), a.getId(), len, kind));
            addedEdgeIds.add(fwd);
            addedEdgeIds.add(rev);
            added += 2;
        }

        return new Result(removed, added, failed, splits, problems, addedEdgeIds, addedNodeIds);
    }

    /** 노드를 다시 쓸 거리(m). 연달아 이을 때 같은 자리를 다시 찍으면 이어지게 하는 값이다. */
    private static final double REUSE_M = 2.0;

    /**
     * 찍은 자리 <b>그대로</b> 노드를 만든다({@link ManualEdgeDTO#ADD_EXACT}).
     *
     * <p>기존 엣지에 정사영하지 않는다 — 아직 그려지지 않은 보도를 사람이 직접 놓으려는 것이므로,
     * 옆에 도로가 있다고 해서 그리로 끌려가면 안 된다. <b>어디에 노드가 필요한지는 사람이 판단한다.</b>
     *
     * <p>다만 {@link #REUSE_M} 안에 노드가 이미 있으면 그것을 쓴다. 안 그러면 A→B, B→C 로
     * 이어 그릴 때 B 자리에 노드가 둘 생겨 선이 끊긴다.
     *
     * <p>이렇게 만든 노드는 <b>이어 붙이기 전까지 그래프에서 떠 있다.</b> 경로가 쓰려면
     * 어딘가에서 기존 그래프와 만나야 한다 — 그 판단도 사람 몫이다.
     */
    private static NodeDTO freeNode(List<NodeDTO> nodes, Map<Long, NodeDTO> byId,
                                    double lat, double lng, long[] id) {
        NodeDTO near = nearest(nodes, lat, lng, REUSE_M);
        if (near != null) {
            return near;
        }
        NodeDTO n = new NodeDTO();
        n.setId(id[0]--);
        n.setLatitude(lat);
        n.setLongitude(lng);
        nodes.add(n);
        byId.put(n.getId(), n);
        return n;
    }

    /**
     * 찍은 좌표에 붙을 노드를 정한다.
     *
     * <ol>
     *   <li>{@code snapMaxM} 안에 기존 노드가 있으면 그것</li>
     *   <li>없으면 {@code edgeMaxM} 안의 가장 가까운 엣지를 <b>그 자리에서 쪼개</b> 새 노드를 만든다</li>
     *   <li>둘 다 없으면 {@code null}</li>
     * </ol>
     *
     * <p>2번이 있어야 보도 <b>한가운데</b>에 횡단보도를 달 수 있다. 기존 노드에만 붙일 수 있으면
     * 노드가 없는 구간에는 아무것도 못 잇는다.
     *
     * <p>쪼갤 때는 원래 엣지(양방향 2개)를 빼고 반쪽 4개를 넣는다. 뒤이어 같은 구간을 또 쪼개면
     * 이미 갱신된 목록에서 해당 반쪽을 찾으므로 여러 번 쪼개도 어긋나지 않는다.
     */
    private static NodeDTO endpoint(List<NodeDTO> nodes, List<EdgeDTO> edges, Map<Long, NodeDTO> byId,
                                    double lat, double lng, double snapMaxM, double edgeMaxM, long[] id) {

        NodeDTO exact = nearest(nodes, lat, lng, snapMaxM);
        if (exact != null) {
            return exact;
        }

        EdgeDTO best = null;
        double bestD = edgeMaxM;
        double[] bestProj = null;
        for (EdgeDTO e : edges) {
            NodeDTO a = byId.get(e.getFromNodeId()), b = byId.get(e.getToNodeId());
            if (a == null || b == null) {
                continue;
            }
            double[] proj = project(lat, lng, a, b);
            double d = RouteGraph.haversineM(lat, lng, proj[0], proj[1]);
            if (d < bestD) {
                bestD = d;
                best = e;
                bestProj = proj;
            }
        }
        if (best == null) {
            return null;
        }

        NodeDTO a = byId.get(best.getFromNodeId()), b = byId.get(best.getToNodeId());

        // 끝점에 거의 붙었으면 굳이 쪼개지 않는다. 길이 0 짜리 조각이 생기는 것을 막는다.
        if (RouteGraph.haversineM(bestProj[0], bestProj[1], a.getLatitude(), a.getLongitude()) < 1.5) {
            return a;
        }
        if (RouteGraph.haversineM(bestProj[0], bestProj[1], b.getLatitude(), b.getLongitude()) < 1.5) {
            return b;
        }

        NodeDTO n = new NodeDTO();
        n.setId(id[0]--);
        n.setLatitude(bestProj[0]);
        n.setLongitude(bestProj[1]);
        nodes.add(n);
        byId.put(n.getId(), n);

        // 원래 구간(양방향)을 빼고 반쪽으로 갈아 끼운다
        String kind = best.getOsmHighway();
        edges.removeIf(e -> (e.getFromNodeId() == a.getId() && e.getToNodeId() == b.getId())
                || (e.getFromNodeId() == b.getId() && e.getToNodeId() == a.getId()));

        double la = RouteGraph.haversineM(a.getLatitude(), a.getLongitude(), n.getLatitude(), n.getLongitude());
        double lb = RouteGraph.haversineM(n.getLatitude(), n.getLongitude(), b.getLatitude(), b.getLongitude());
        edges.add(edge(id[0]--, a.getId(), n.getId(), la, kind));
        edges.add(edge(id[0]--, n.getId(), a.getId(), la, kind));
        edges.add(edge(id[0]--, n.getId(), b.getId(), lb, kind));
        edges.add(edge(id[0]--, b.getId(), n.getId(), lb, kind));

        return n;
    }

    /** 점을 선분에 정사영한 좌표. 짧은 구간이라 위경도를 평면으로 근사해도 오차가 무시할 수준이다. */
    private static double[] project(double lat, double lng, NodeDTO a, NodeDTO b) {
        double kx = Math.cos(Math.toRadians(lat));
        double ax = a.getLongitude() * kx, ay = a.getLatitude();
        double bx = b.getLongitude() * kx, by = b.getLatitude();
        double px = lng * kx, py = lat;

        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return new double[]{ay + t * dy, (ax + t * dx) / kx};
    }

    /** 저장된 두 좌표가 이 엣지의 양 끝과 (방향 무관) 맞는가. */
    private static boolean samePair(ManualEdgeDTO m, NodeDTO a, NodeDTO b, double tol) {
        double aa = RouteGraph.haversineM(m.getFromLat(), m.getFromLng(), a.getLatitude(), a.getLongitude());
        double bb = RouteGraph.haversineM(m.getToLat(), m.getToLng(), b.getLatitude(), b.getLongitude());
        if (aa <= tol && bb <= tol) {
            return true;
        }
        double ab = RouteGraph.haversineM(m.getFromLat(), m.getFromLng(), b.getLatitude(), b.getLongitude());
        double ba = RouteGraph.haversineM(m.getToLat(), m.getToLng(), a.getLatitude(), a.getLongitude());
        return ab <= tol && ba <= tol;
    }

    private static NodeDTO nearest(List<NodeDTO> nodes, double lat, double lng, double maxM) {
        NodeDTO best = null;
        double bestD = maxM;
        for (NodeDTO n : nodes) {
            double d = RouteGraph.haversineM(lat, lng, n.getLatitude(), n.getLongitude());
            if (d < bestD) {
                bestD = d;
                best = n;
            }
        }
        return best;
    }

    private static EdgeDTO edge(long id, long from, long to, double lengthM, String kind) {
        EdgeDTO e = new EdgeDTO();
        e.setId(id);
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setLengthM(lengthM);
        e.setOsmHighway(kind);
        return e;
    }
}

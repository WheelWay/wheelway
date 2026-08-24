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
 * <h3>적용 순서 — <b>기록된 순서 그대로(ID 오름차순)</b></h3>
 * 예전에는 <b>삭제를 전부 먼저</b> 돌리고 그다음 추가를 돌렸다. "이 엣지를 지우고 대신
 * 저기로 잇겠다" 는 흐름을 지키려던 것인데, <b>반대 흐름을 통째로 막고 있었다</b> —
 * 사람이 직접 그린 엣지를 지우려 하면 삭제가 먼저 도는데 그때는 그 엣지가 아직 없다.
 * 못 찾고 실패한 뒤 추가가 다시 만든다. <b>직접 그린 것은 영원히 못 지웠다.</b>
 * (2026-08-24 실측: 그때까지 쌓인 삭제 기록 4건이 전부 이 이유로 실패하고 있었다.)
 *
 * <p>이제 <b>기록한 순서대로 되짚는다.</b> 지우고 잇든, 잇고 지우든 사람이 한 순서 그대로
 * 재현된다. 원래 지키려던 흐름도 그대로 지켜진다 — 그 사람은 삭제를 먼저 저장했으니까.
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
    public record Result(int removed, int added, int splitNodes,
                         Map<Long, String> problemById,
                         Set<Long> addedEdgeIds, Set<Long> addedNodeIds) {

        public Result(int removed, int added, int splitNodes, Map<Long, String> problemById) {
            this(removed, added, splitNodes, problemById, Set.of(), Set.of());
        }

        /**
         * 실패를 <b>{@code id → 사유} 한 곳</b>에만 담는다. 예전에는 개수·문구·ID 집합을
         * 따로 들고 있었는데, 뒤에서 실패를 하나 취소할 때(연결 엣지 재시도) 셋을 모두
         * 맞춰 고쳐야 해서 어긋나기 쉬웠다.
         */
        public int failed() {
            return problemById.size();
        }

        public List<String> problems() {
            return List.copyOf(problemById.values());
        }

        public Set<Long> failedIds() {
            return problemById.keySet();
        }

        /**
         * {@code id} 기록이 이번 적용에서 <b>반영에 실패</b>했는가.
         *
         * <p>화면이 '방금 누른 저장이 먹었는가' 와 '예전 기록이 아직 안 먹는가' 를 갈라
         * 말하려고 쓴다. 이걸 안 나누면 절대 성공할 수 없는 옛 기록 하나가
         * <b>이후 모든 저장을 실패로 보이게 한다.</b>
         */
        public boolean failedFor(long id) {
            return problemById.containsKey(id);
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

        Map<Long, String> problems = new java.util.LinkedHashMap<>();
        int removed = 0, added = 0;

        /*
         * 기록한 순서대로 되짚는다. 화면은 최신순(ID DESC)으로 받아오므로 여기서 뒤집는다.
         * 원본 목록을 건드리지 않으려고 복사본을 정렬한다 — 호출한 쪽이 같은 목록을
         * 화면에 그대로 쓰고 있을 수 있다.
         */
        List<ManualEdgeDTO> ordered = new ArrayList<>(manual);
        ordered.sort(java.util.Comparator.comparingLong(ManualEdgeDTO::getId));

        long[] id = {nextId};
        int splits = 0;
        // 사람이 만든 것만 따로 모은다. 화면이 '수정 모드일 때만' 보여주려면 구분이 필요하고,
        // 자동 보도연결(SidewalkConnector)이 이 노드들을 건드리지 않게 하는 데도 쓴다.
        Set<Long> addedEdgeIds = new HashSet<>();
        Set<Long> addedNodeIds = new HashSet<>();

        for (ManualEdgeDTO m : ordered) {

        // ---- 삭제 : 양 끝 좌표가 모두 가까운 엣지를 뺀다(양방향 다) ----
        if (ManualEdgeDTO.REMOVE.equals(m.getAction())) {
            /*
             * ★ 허용오차 안에 드는 것을 전부 지우지 않는다. <b>가장 잘 맞는 하나</b>를 고른다.
             *
             * 예전에는 5m 안에 양 끝이 들어오는 엣지를 모조리 지웠다. 짧은 엣지에서는
             * 그 오차가 엣지 길이보다 커서 <b>옆에 나란한 엣지까지 같이 물어간다</b> —
             * 실측(2026-08-24): 3m 짜리 보도 하나를 지웠더니 엣지가 6개(3쌍) 사라졌다.
             * 손으로 촘촘히 그린 구역일수록 심해진다.
             *
             * 그래서 오차 안에서 <b>양 끝 거리의 최댓값이 가장 작은</b> 노드 쌍 하나만 고르고,
             * 그 두 노드 사이의 엣지(양방향)만 뺀다.
             */
            long bestFrom = 0, bestTo = 0;
            double bestScore = Double.MAX_VALUE;
            for (EdgeDTO e : edges) {
                NodeDTO a = byId.get(e.getFromNodeId()), b = byId.get(e.getToNodeId());
                if (a == null || b == null) {
                    continue;
                }
                double score = pairScore(m, a, b);
                if (score <= matchMaxM && score < bestScore) {
                    bestScore = score;
                    bestFrom = e.getFromNodeId();
                    bestTo = e.getToNodeId();
                }
            }

            int hit = 0;
            if (bestScore <= matchMaxM) {
                final long f = bestFrom, t = bestTo;
                for (Iterator<EdgeDTO> it = edges.iterator(); it.hasNext(); ) {
                    EdgeDTO e = it.next();
                    if ((e.getFromNodeId() == f && e.getToNodeId() == t)
                            || (e.getFromNodeId() == t && e.getToNodeId() == f)) {
                        it.remove();
                        hit++;
                    }
                }
            }
            if (hit == 0) {
                problems.put(m.getId(), "삭제 #" + m.getId() + " 대상 엣지를 못 찾음 ("
                        + matchMaxM + "m 안에 일치하는 엣지 없음)");
            } else {
                removed += hit;
            }
            continue;
        }

        // ---- 추가 : 양 끝에 붙을 노드를 정하고(없으면 엣지를 쪼개 만들고) 잇는다 ----
        {
            boolean exact = ManualEdgeDTO.ADD_EXACT.equals(m.getAction());
            boolean nodeOnly = ManualEdgeDTO.NODE.equals(m.getAction());
            if (!exact && !nodeOnly && !ManualEdgeDTO.ADD.equals(m.getAction())) {
                continue;
            }
            int before = nodes.size();

            /*
             * 노드만 놓는 기록. 엣지를 만들지 않으므로 여기서 끝난다.
             *
             * 실패할 수가 없다 - 붙일 곳을 찾는 일이 없기 때문이다. 같은 자리(REUSE_M)에
             * 이미 노드가 있으면 그것을 쓰고 아무것도 안 늘어나는데, 그것도 실패가 아니다.
             * 화면이 '엣지 수가 그대로' 를 실패로 읽지 않도록 응답에 노드 수도 같이 싣는다.
             */
            if (nodeOnly) {
                freeNode(nodes, byId, m.getFromLat(), m.getFromLng(), id);
                for (int k = before; k < nodes.size(); k++) {
                    addedNodeIds.add(nodes.get(k).getId());
                }
                continue;
            }

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
                problems.put(m.getId(), "추가 #" + m.getId() + " 붙일 곳을 못 찾음 ("
                        + (int) snapMaxM + "m 안에 노드도, " + (int) edgeMaxM + "m 안에 엣지도 없음)");
                continue;
            }
            if (a.getId() == b.getId()) {
                problems.put(m.getId(),
                        "추가 #" + m.getId() + " 양 끝이 같은 노드에 붙음 — 두 점을 더 벌려 찍을 것");
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
        }

        return new Result(removed, added, splits, problems, addedEdgeIds, addedNodeIds);
    }

    /**
     * 1차에서 <b>대상을 못 찾은 삭제</b>를 한 번 더 시도한다. 연결 엣지가 붙은 뒤에 부른다.
     *
     * <h3>왜 두 번 도는가</h3>
     * {@link SidewalkConnector} 가 만드는 자동 보도연결 엣지는 {@link #apply} <b>다음에</b>
     * 그래프에 붙는다. 그래서 그 엣지를 지우라는 기록은 1차에서 대상을 볼 수가 없다 —
     * 관리자가 화면에서 분명히 클릭해 지웠는데 <b>영원히 안 지워진다.</b>
     * (2026-08-24 실측: 삭제 기록 18건 중 15건이 이 이유로 실패하고 있었다.
     * 교차로 주변에 부챗살처럼 뻗은 초록 선이 대부분 이 연결 엣지다.)
     *
     * <p>1차에서 이미 성공한 삭제는 건드리지 않는다. 실패로 남은 것만 다시 본다.
     *
     * @return 재시도 결과를 반영한 새 {@link Result}. 고칠 것이 없으면 {@code prev} 그대로
     */
    public static Result retryRemovals(List<NodeDTO> nodes, List<EdgeDTO> edges,
                                       List<ManualEdgeDTO> manual, double matchMaxM, Result prev) {
        if (prev.problemById().isEmpty()) {
            return prev;
        }

        Map<Long, NodeDTO> byId = new HashMap<>(nodes.size() * 2);
        for (NodeDTO n : nodes) {
            byId.put(n.getId(), n);
        }

        Map<Long, String> problems = new java.util.LinkedHashMap<>(prev.problemById());
        int removed = prev.removed();

        for (ManualEdgeDTO m : manual) {
            if (!ManualEdgeDTO.REMOVE.equals(m.getAction()) || !problems.containsKey(m.getId())) {
                continue;
            }

            long bestFrom = 0, bestTo = 0;
            double bestScore = Double.MAX_VALUE;
            for (EdgeDTO e : edges) {
                NodeDTO a = byId.get(e.getFromNodeId()), b = byId.get(e.getToNodeId());
                if (a == null || b == null) {
                    continue;
                }
                double score = pairScore(m, a, b);
                if (score <= matchMaxM && score < bestScore) {
                    bestScore = score;
                    bestFrom = e.getFromNodeId();
                    bestTo = e.getToNodeId();
                }
            }
            if (bestScore > matchMaxM) {
                continue;                      // 연결 엣지에도 없다. 실패로 남는다
            }

            final long f = bestFrom, t = bestTo;
            int hit = 0;
            for (Iterator<EdgeDTO> it = edges.iterator(); it.hasNext(); ) {
                EdgeDTO e = it.next();
                if ((e.getFromNodeId() == f && e.getToNodeId() == t)
                        || (e.getFromNodeId() == t && e.getToNodeId() == f)) {
                    it.remove();
                    hit++;
                }
            }
            if (hit > 0) {
                problems.remove(m.getId());
                removed += hit;
            }
        }

        return new Result(removed, prev.added(), prev.splitNodes(), problems,
                prev.addedEdgeIds(), prev.addedNodeIds());
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

    /**
     * 저장된 두 좌표가 이 엣지의 양 끝과 얼마나 맞는가(m). 작을수록 잘 맞는다.
     *
     * <p>양 끝 거리의 <b>최댓값</b>을 쓴다 — 한쪽만 딱 맞고 반대쪽이 멀면 다른 엣지다.
     * 방향은 상관없으므로 뒤집어서도 재고 더 나은 쪽을 쓴다.
     */
    private static double pairScore(ManualEdgeDTO m, NodeDTO a, NodeDTO b) {
        double aa = RouteGraph.haversineM(m.getFromLat(), m.getFromLng(), a.getLatitude(), a.getLongitude());
        double bb = RouteGraph.haversineM(m.getToLat(), m.getToLng(), b.getLatitude(), b.getLongitude());
        double ab = RouteGraph.haversineM(m.getFromLat(), m.getFromLng(), b.getLatitude(), b.getLongitude());
        double ba = RouteGraph.haversineM(m.getToLat(), m.getToLng(), a.getLatitude(), a.getLongitude());
        return Math.min(Math.max(aa, bb), Math.max(ab, ba));
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

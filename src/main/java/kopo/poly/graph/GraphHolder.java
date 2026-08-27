package kopo.poly.graph;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import kopo.poly.dto.BlockedPointDTO;
import kopo.poly.dto.EdgeDTO;
import kopo.poly.dto.ManualEdgeDTO;
import kopo.poly.dto.NodeDTO;
import kopo.poly.mapper.IGraphMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 서버 기동 시 그래프를 한 번 올리고 계속 들고 있는다. 요청마다 DB 를 다시 읽지 않는다.
 *
 * <p>기동 순서는 이렇다.
 * <ol>
 *   <li>NODES / EDGES 조회 — 계단은 {@code EXCLUDE_REASON IS NULL} 조건에서 이미 빠진다 (하드필터 1)</li>
 *   <li>인접리스트 구성</li>
 *   <li>공사구간·제보 좌표를 엣지 ID 로 바꿔 차단 Set 구성 (하드필터 2, 3)</li>
 * </ol>
 *
 * <p>DB 가 비어 있어도 기동은 실패시키지 않는다. 적재 전에 서버를 띄워보는 일이 흔하고,
 * 그때 애플리케이션 전체가 안 뜨면 원인을 찾기가 더 번거로워진다. 대신 경고를 크게 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphHolder {

    private final IGraphMapper graphMapper;
    private final BlockedEdges blockedEdges;

    @Value("${wheelway.region-id}")
    private String regionId;

    /**
     * 공사구간·제보 좌표 하나가 반경 몇 m 안의 엣지를 막을지 — <b>기본값</b>이다.
     * 공사구간은 행마다 {@code BLOCK_RADIUS_M} 으로 이 값을 덮어쓸 수 있다.
     */
    @Value("${wheelway.block-radius-m}")
    private double blockRadiusM;

    /**
     * 반경 안에 보도가 없을 때 '가장 가까운 구간'을 찾아볼 최대 거리.
     * 이것마저 넘으면 그래프에서 너무 먼 좌표라 보고 포기한다.
     */
    @Value("${wheelway.block-fallback-max-m}")
    private double fallbackMaxM;

    /**
     * OSM {@code highway} → 경로 비용 가중치. {@code cost = length_m × weight}.
     *
     * <p>보도(1.0)보다 차도를 비싸게 만들어 <b>보도가 있으면 보도로 가게</b> 한다.
     * 하드필터로 차도를 지우지 않는 이유는 OSM 인도 태그 커버리지가 7.1% 라,
     * 지우면 인도가 안 그려진 구간이 통째로 끊기기 때문이다.
     */
    @Value("#{${wheelway.highway-weights}}")
    private java.util.Map<String, Double> highwayWeights;

    /** 표에 없는 highway 값(신규 태그·NULL)에 쓸 값. 보도로 오인하지 않도록 1.0 보다 크게 둔다. */
    @Value("${wheelway.highway-weight-default}")
    private double highwayWeightDefault;

    /**
     * 출발·도착을 그래프에 붙일 때 <b>보도 노드를 이만큼 더 멀어도 먼저</b> 잡는다(m).
     *
     * <p>가중치와 다른 문제를 푼다. 가중치는 '어느 길로 갈까' 이고 이 값은
     * '어디서 시작하고 어디서 끝낼까' 다 — 보도를 아무리 싸게 만들어도
     * 끝점이 차도 중심선이면 경로는 차도에서 끝난다.
     */
    @Value("${wheelway.snap-prefer-walk-m:20}")
    private double snapPreferWalkM;

    /**
     * 교차로에서 이 거리 안에 있는 보도 노드를 이어준다. {@code 0} 이면 기능을 끈다.
     * {@link SidewalkConnector} 참고 — 보도를 새로 그리는 게 아니라 있는 보도에 문만 단다.
     */
    @Value("${wheelway.sidewalk-connect-max-m}")
    private double connectorMaxM;

    /** 수동 엣지의 좌표를 <b>기존 노드</b>에 붙일 최대 거리. */
    @Value("${wheelway.manual-edge-snap-max-m}")
    private double manualSnapMaxM;

    /**
     * 기존 노드가 없을 때 <b>엣지를 쪼개서</b> 노드를 만들 최대 거리.
     * 보도 한가운데에 횡단보도를 다는 경우가 이것이다.
     */
    @Value("${wheelway.manual-edge-split-max-m}")
    private double manualEdgeMaxM;

    /** 수동 '삭제' 가 대상 엣지를 찾을 때 허용할 오차. 좌표로 다시 찾아야 하므로 필요하다. */
    @Value("${wheelway.manual-edge-match-max-m}")
    private double manualMatchMaxM;


    /** 이번 로드에서 차선책(가장 가까운 구간)으로 막은 지점 수. 좌표를 손봐야 할 건수다. */
    private int fallbackCount;

    /**
     * 이번 로드에서 수동 엣지가 어떻게 반영됐는지.
     *
     * <p>화면에 돌려주려고 들고 있는다. <b>저장은 됐는데 반영은 안 된 경우</b>가 실제로 생긴다 —
     * 이미 지운 엣지를 또 지우려 하거나, 좌표가 그래프에서 멀 때다.
     * 이걸 안 알려주면 사용자는 성공한 줄 알고 넘어간다.
     */
    private ManualEdges.Result lastManual = new ManualEdges.Result(0, 0, 0, java.util.Map.of());

    private RouteGraph graph;


    @PostConstruct
    public void load() {
        long begin = System.currentTimeMillis();

        List<NodeDTO> nodes = graphMapper.getNodes(regionId);
        List<EdgeDTO> edges = graphMapper.getRoutableEdges(regionId);

        // 사람이 직접 넣고 뺀 엣지를 먼저 반영한다. 자동 연결보다 앞이라야
        // 사람이 이어둔 곳을 자동 연결이 중복으로 잇지 않는다.
        List<ManualEdgeDTO> manual = graphMapper.getManualEdges(regionId);
        ManualEdges.Result mr = ManualEdges.apply(nodes, edges, manual,
                manualSnapMaxM, manualEdgeMaxM, manualMatchMaxM, -1L);
        lastManual = mr;

        // 교차로와 '이미 그려진' 보도를 잇는다. DB 에는 쓰지 않고 메모리 그래프에만 더한다 —
        // 원본 OSM 적재를 건드리지 않아야 재적재해도 같은 결과가 나온다.
        if (connectorMaxM > 0) {
            /*
             * ★ 수동 반영이 <b>실제로 마지막에 쓴 ID</b> 다음부터 이어 쓴다.
             *
             * 예전에는 -1 - added 로 계산했는데, 그 카운터는 추가한 엣지만 세는 값이다.
             * 같은 카운터를 새 노드(freeNode·분할 노드)와 분할로 생긴 반쪽 엣지 4개도
             * 같이 쓰므로, added 만 빼면 <b>이미 나눠준 ID 를 연결 엣지에 다시 준다.</b>
             * (2026-08-26 실측: 청주 수동 219건 기준 연결 엣지 100개가 수동 엣지와 같은 ID 였다.)
             *
             * ID 가 겹치면 두 가지가 조용히 깨진다.
             *   - 화면이 수동 엣지를 감출 때(addedEdgeIds) 같은 ID 의 연결 엣지까지 사라진다
             *   - 차단 Set 이 ID 로 도니까, 제보 하나가 연결 엣지를 막으면
             *     <b>손으로 그린 보도가 같이 막힌다</b> — 경로는 그 보도를 두고 차도로 돈다
             */
            long nextId = mr.nextId();
            // 사람이 직접 찍은 노드는 빼고 잇는다. 손으로 그린 보도를 옆 도로가 자동으로
            // 물어가면 '잇지도 않은 도보와 차도가 이어진' 그래프가 된다.
            List<EdgeDTO> links = SidewalkConnector.build(nodes, edges, connectorMaxM, nextId,
                    mr.addedNodeIds());
            edges.addAll(links);
            log.info("보도 연결 엣지 {}개 추가 (교차로 기준 {}m 이내, 수동 노드 {}개 제외)",
                    links.size(), connectorMaxM, mr.addedNodeIds().size());

            /*
             * ★ 연결 엣지가 붙은 뒤에 '대상을 못 찾은 삭제' 를 한 번 더 시도한다.
             * 자동 연결 엣지는 여기서야 존재하므로, 그것을 지우라는 기록은 1차에서 볼 수가 없다.
             * 이걸 안 하면 관리자가 화면에서 분명히 클릭해 지운 선이 영영 안 지워진다.
             */
            int before = mr.failed();
            mr = ManualEdges.retryRemovals(nodes, edges, manual, manualMatchMaxM, mr);
            lastManual = mr;
            if (before != mr.failed()) {
                log.info("연결 엣지까지 보고 삭제 {}건을 더 반영했다", before - mr.failed());
            }
        }

        if (!manual.isEmpty()) {
            log.info("수동 엣지 {}건 반영 — 삭제 {}개 / 추가 {}개 / 분할 노드 {}개 / 실패 {}건",
                    manual.size(), mr.removed(), mr.added(), mr.splitNodes(), mr.failed());
            mr.problems().forEach(s -> log.warn("수동 엣지: {}", s));
        }

        graph = RouteGraph.build(regionId, nodes, edges, this::weightOf, snapPreferWalkM);

        log.info("그래프 로드 region={} 노드={} 엣지={} ({}ms)",
                regionId, graph.nodeCount(), graph.edgeCount(), System.currentTimeMillis() - begin);
        log.info("도로종류 가중치 {} (표에 없으면 {})", highwayWeights, highwayWeightDefault);
        log.info("스냅 보도 우대 {}m", snapPreferWalkM);

        if (graph.nodeCount() == 0) {
            log.warn("region='{}' 에 노드가 없습니다. OsmGraphLoader 로 적재하지 않았거나 region-id 가 다릅니다.", regionId);
            return;
        }
        if (graph.droppedEdgeCount() > 0) {
            log.warn("양 끝 노드를 찾지 못해 버린 엣지 {}개. 적재가 중간에 끊겼는지 확인하세요.", graph.droppedEdgeCount());
        }

        reloadBlockedEdges();
        logComponents();
    }

    /** 마지막 로드에서 수동 엣지가 어떻게 반영됐는지. 화면이 '저장은 됐는데 반영 안 됨'을 알리는 근거다. */
    public ManualEdges.Result getLastManual() {
        return lastManual;
    }

    /** OSM highway 값의 가중치. 표에 없거나 NULL 이면 기본값을 쓴다. */
    public double weightOf(String osmHighway) {
        if (osmHighway == null) {
            return highwayWeightDefault;
        }
        return highwayWeights.getOrDefault(osmHighway, highwayWeightDefault);
    }

    /**
     * 차단 Set 을 DB 에서 다시 구성한다. 기동 시 1회 호출되고,
     * 공사 데이터를 새로 적재한 뒤 수동으로 다시 부를 수도 있다.
     *
     * <p>제보는 등록 즉시 {@link BlockedEdges#block(long)} 으로 반영되므로 여기를 거치지 않는다.
     * 이 메서드는 '지금까지 쌓인 것 전체'를 다시 계산하는 쪽이다.
     */
    public void reloadBlockedEdges() {
        Set<Long> blocked = new HashSet<>();
        fallbackCount = 0;

        List<BlockedPointDTO> construction = graphMapper.getActiveConstructionPoints(regionId);
        int constructionEdges = collect(construction, blocked);

        List<BlockedPointDTO> reports = graphMapper.getBlockingReportPoints(regionId);
        int reportEdges = collect(reports, blocked);

        blockedEdges.replaceAll(blocked);

        log.info("차단 엣지 {}개 — 공사구간 {}건→{}엣지, 높음제보 {}건→{}엣지 (반경 {}m)",
                blocked.size(), construction.size(), constructionEdges, reports.size(), reportEdges, blockRadiusM);

        if (construction.isEmpty()) {
            log.info("공사구간 0건 — CONSTRUCTION_ZONES 가 비었거나 좌표(지오코딩)가 아직 없습니다. 이 단계에서는 정상입니다.");
        }
        if (fallbackCount > 0) {
            log.info("이 중 {}건은 반경 안에 보도가 없어 가장 가까운 구간으로 대체했습니다. "
                    + "화면 수정 모드에서 좌표를 보도 위로 옮기면 정확해집니다.", fallbackCount);
        }
    }

    /**
     * 차단 지점들을 엣지 ID 로 바꿔 {@code into} 에 넣는다.
     *
     * @return 이번에 찾아낸 엣지 수(중복 포함 전 기준이 아니라, 실제로 매칭된 총 개수)
     */
    private int collect(List<BlockedPointDTO> points, Set<Long> into) {
        int found = 0;
        for (BlockedPointDTO p : points) {
            // 제보에 엣지 ID 가 이미 박혀 있으면 좌표 매칭을 건너뛴다.
            if (p.getEdgeId() != null) {
                into.add(p.getEdgeId());
                found++;
                continue;
            }

            double radius = radiusOf(p);
            Set<Long> near = matchEdges(p.getLatitude(), p.getLongitude(), radius);

            if (near.isEmpty()) {
                log.warn("차단 지점 '{}' ({}, {}) 에서 {}m 안에 보도가 전혀 없습니다. 무시합니다.",
                        p.getLabel(), p.getLatitude(), p.getLongitude(), fallbackMaxM);
                continue;
            }
            if (graph.edgeIdsNear(p.getLatitude(), p.getLongitude(), radius).isEmpty()) {
                log.info("차단 지점 '{}' 반경 {}m 안에 보도가 없어 가장 가까운 구간을 막았습니다. 좌표 확인 필요.",
                        p.getLabel(), radius);
                fallbackCount++;
            }

            into.addAll(near);
            found += near.size();
        }
        return found;
    }

    /**
     * 좌표 하나를 막을 엣지로 바꾸는 <b>단일 규칙</b>. 화면(컨트롤러)도 이걸 그대로 써야
     * 지도에 보이는 것과 실제 차단이 어긋나지 않는다.
     *
     * <ol>
     *   <li>반경 안의 엣지</li>
     *   <li>없으면 상한 안에서 가장 가까운 엣지 하나 — 좌표가 필지 중심에 찍혀 보도와 멀어진 경우다.
     *       공사가 기록돼 있는 이상 무언가는 막혀야 하므로 없는 셈 치지 않는다</li>
     * </ol>
     *
     * <p>한때 여기서 보도 계열을 우선하도록 했다가 되돌렸다. 원본에 '도로 어느 쪽 보도인지'가 없어
     * 반대편 보도를 고르는 일이 생겼고, 좌표만으로는 판별할 수 없다.
     */
    public Set<Long> matchEdges(double lat, double lon, double radius) {
        Set<Long> near = graph.edgeIdsNear(lat, lon, radius);
        return near.isEmpty() ? graph.edgeIdsNearest(lat, lon, fallbackMaxM) : near;
    }

    /** {@link #matchEdges} 와 같은 순서로 좌표열을 돌려준다. 화면에 그리는 용도다. */
    public List<double[][]> matchSegments(double lat, double lon, double radius) {
        List<double[][]> near = graph.segmentsNear(lat, lon, radius);
        return near.isEmpty() ? graph.segmentsNearest(lat, lon, fallbackMaxM) : near;
    }

    /** 이 지점의 차단 반경. 행에 값이 있으면 그것을, 없으면 전역 설정값을 쓴다. */
    public double radiusOf(BlockedPointDTO p) {
        return (p.getBlockRadiusM() == null) ? blockRadiusM : p.getBlockRadiusM();
    }

    /** 전역 기본 반경. 화면에서 '설정값 사용'이 몇 m 인지 보여줄 때 쓴다. */
    public double getDefaultBlockRadiusM() {
        return blockRadiusM;
    }

    /** 그래프가 몇 덩어리로 끊겨 있는지 기동 로그에 남긴다. '경로없음'을 해석할 때 필요한 정보다. */
    private void logComponents() {
        List<Integer> sizes = graph.componentSizes();
        if (sizes.isEmpty()) {
            return;
        }
        int largest = sizes.get(0);
        int connected = graph.connectedNodeCount();

        log.info("연결 덩어리 {}개, 최대 {}개 노드 (연결된 노드 {}개 중 {}%)",
                sizes.size(), largest, connected, Math.round(1000.0 * largest / connected) / 10.0);
        log.info("계단으로만 이어져 있어 고립된 노드 {}개 — 스냅 대상에서 제외됩니다.",
                graph.nodeCount() - connected);
    }

    public RouteGraph getGraph() {
        return graph;
    }


    public String getRegionId() {
        return regionId;
    }
}

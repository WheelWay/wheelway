package kopo.poly.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 경로 탐색 결과.
 *
 * <p>{@code resultStatus} 값은 {@code ROUTE_SEARCH_LOGS.RESULT_STATUS} 에 그대로 들어가도록 맞춰뒀다.
 */
@Getter
@Setter
public class RouteResultDTO {

    public static final String STATUS_SUCCESS = "성공";
    public static final String STATUS_NO_ROUTE = "경로없음";
    public static final String STATUS_ERROR = "오류";

    private String resultStatus;

    /** 실패 원인을 사람이 읽을 수 있게. 스냅 실패인지 진짜 경로가 없는 건지 구분하려고 둔다. */
    private String message;

    /** 경로 총 길이(m). 이번 단계는 cost 가 곧 거리라 cost 합과 같다. */
    private double distanceM;

    /** 탐색에 걸린 시간(ms). {@code ROUTE_SEARCH_LOGS.DURATION_MS} 용. */
    private int durationMs;

    /**
     * 경로 좌표열. {@code [[위도,경도], ...]} — 위도 먼저다.
     * {@code EDGES.GEOMETRY_JSON} 과 같은 순서라 카카오맵 폴리라인에 그대로 넘길 수 있다.
     */
    private List<double[]> path;

    /**
     * 지나간 엣지 ID. 검증용이다.
     * <b>이 목록에 차단된 엣지가 하나라도 있으면 하드필터가 뚫린 것이다.</b>
     */
    private List<Long> edgeIds;

    /**
     * 지나간 엣지의 도로종류({@code OSM_HIGHWAY}). {@code edgeIds} 와 같은 순서·같은 길이다.
     * {@code path} 는 이보다 1 개 많다 — {@code path[i] → path[i+1]} 구간이 {@code edgeKinds[i]} 다.
     *
     * <p>화면이 보도와 차도를 다르게 그리려면 필요하다. 차도 중심선 위에 그려진 선을
     * 양옆으로 밀어 보여줄 때 <b>어느 구간을 밀면 안 되는지</b>(이미 보도인 구간)를 이걸로 판단한다.
     */
    private List<String> edgeKinds;


    /** 스냅된 출발/도착 노드. 어디에 붙었는지 확인할 때 쓴다. */
    private Long startNodeId;
    private Long endNodeId;

    /** 원좌표에서 스냅된 노드까지의 거리(m). 이 값이 크면 엉뚱한 곳에 붙은 것이다. */
    private double startSnapM;
    private double endSnapM;

    /** 우선순위 큐에서 꺼낸 노드 수. Dijkstra 와 A* 를 비교할 때 이 값이 근거가 된다. */
    private int visitedNodes;
}

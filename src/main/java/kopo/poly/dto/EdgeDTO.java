package kopo.poly.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * EDGES 조회 결과. 방향이 있는 엣지 1개다(같은 구간의 반대 방향은 별도 행·별도 ID).
 *
 * <p>이번 단계의 cost 는 {@code lengthM} 하나뿐이다. 하드필터와 탐색이 정상 동작하는지 먼저 보려는 것이라
 * {@code SLOPE_PERCENT}/{@code ROAD_GRADE} 는 조회하지 않는다. 소프트 스코어링을 붙일 때
 * {@code cost = lengthM × slopeWeight} 가 되면서 {@code slopePercent} 가 여기 추가된다.
 *
 * <p>{@code GEOMETRY_JSON} 도 조회하지 않는다. 지금은 세그먼트 단위 엣지라 중간점이 없어서
 * 경로 폴리라인을 노드 좌표만으로 그릴 수 있고, 5만 행의 TEXT 컬럼을 메모리에 올릴 이유가 없다.
 */
@Getter
@Setter
public class EdgeDTO {

    /** EDGES.ID — 차단 Set 이 담는 값이 바로 이것이다. */
    private long id;

    private long fromNodeId;
    private long toNodeId;

    /** 실제 거리(m). cost 의 바탕이 되는 값이며, 화면에 찍는 '총 거리'도 이것의 합이다. */
    private double lengthM;

    /**
     * OSM {@code highway} 태그 원본. 보도 계열인지 차도 계열인지 가르는 유일한 근거다.
     *
     * <p><b>차도를 그래프에서 빼지 않고 이 값으로 비싸게 만드는 이유</b>:
     * OSM 인도 태그 커버리지가 중구 대로 기준 7.1% 라, 차도를 지우면 인도가 안 그려진 구간이
     * 통째로 끊긴다. 연결은 살리고 비용만 올려야 보도가 있으면 보도로 가고 없으면 차도로 간다.
     *
     * <p>한때 '공사 위치 추정선'을 만들며 이 필드를 넣었다가 그 기능을 되돌리면서 같이 지웠는데,
     * 지금은 <b>다른 목적</b>(경로 비용)으로 다시 쓴다. 도로 폭을 추정하지 않으므로 그때의 문제와 무관하다.
     */
    private String osmHighway;
}

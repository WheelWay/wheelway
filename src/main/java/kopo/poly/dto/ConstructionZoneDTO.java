package kopo.poly.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 화면에 표시할 공사구간.
 *
 * <p>공사기간을 EDGES 컬럼으로 흡수하지 않고 독립 테이블로 둔 이유가 이것이다 —
 * "이 구간은 OO공사로 X월 X일까지 통행 불가"처럼 <b>공사명과 기간을 보여줘야</b> 하는데,
 * EDGES 에 날짜만 심으면 크롤링으로 확보한 공사명·공사구간 원문이 전부 버려진다.
 */
@Getter
@Setter
public class ConstructionZoneDTO {

    private long id;

    /** 공사명 — 화면 표시용. */
    private String name;

    /** 공사구간 원문(크롤링 원본). 지오코딩 결과가 이상할 때 대조할 근거가 된다. */
    private String roadSegment;

    private double latitude;
    private double longitude;

    /**
     * 이 공사만의 차단 반경(m). {@code null} 이면 전역 설정({@code wheelway.block-radius-m})을 쓴다.
     * 화면에서 이 값을 조절해 교차로를 통째로 막는 문제를 건별로 잡는다.
     */
    private Double blockRadiusM;

    /**
     * <b>표시 전용</b> — 사람이 지도에서 그린 '실제 공사는 여기' 선. {@code [[위도,경도],[위도,경도]]}.
     *
     * <p>지오코딩 좌표는 필지 중심이라 실제 공사 위치와 다르고, '도로 어느 쪽 보도인지'는
     * 데이터로 알 수 없다. 자동으로 맞추려던 시도(보도 우선 매칭, 도로 폭 추정)가 둘 다 실패해
     * <b>사람이 본 것을 그대로 적어두는 칸</b>을 뒀다.
     *
     * <p><b>차단 계산에 절대 쓰지 않는다.</b> 차단은 그래프 엣지에서만 나와야 한다 —
     * Dijkstra 는 엣지 단위로 돌기 때문에, 엣지가 없는 곳에 선을 그어도 막히지 않는다.
     * 이 값을 매칭에 끌어다 쓰는 순간 '지도에 보이는 선'과 '실제 차단'이 어긋나기 시작한다.
     * 그래서 이름에 {@code block} 을 쓰지 않았다.
     *
     * <p>{@link #segments} 와 헷갈리지 말 것 — 저쪽은 실제로 막히는 엣지고, 이쪽은 사람의 메모다.
     */
    private String noteLineJson;

    /** {@code yyyy-MM-dd} 문자열로 받는다. 화면에 그대로 찍기 위한 것이라 날짜 연산을 하지 않는다. */
    private String startDate;
    private String endDate;

    /**
     * 이 공사가 실제로 막는 보도 구간의 좌표열. DB 컬럼이 아니라 컨트롤러가 채운다.
     *
     * <p>크롤링 원문의 '공사구간'은 {@code A ~ B} 형식이지만 적재된 60건은 전부 A 와 B 가 같은 주소라,
     * 원본만으로는 선을 그릴 수 없다. 대신 지오코딩된 점에서 {@code wheelway.block-radius-m} 안에 드는
     * 엣지를 그리면 <b>실제 차단 범위가 그대로 선으로 드러난다.</b>
     *
     * <p>형식: {@code [[[위도,경도],[위도,경도]], ...]}
     */
    private List<double[][]> segments;
}

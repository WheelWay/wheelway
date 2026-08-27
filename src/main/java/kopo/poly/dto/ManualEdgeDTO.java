package kopo.poly.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 사람이 지도에서 직접 넣거나 뺀 엣지 하나.
 *
 * <p><b>왜 좌표로 저장하는가</b>: {@code EDGES.ID} 는 AUTO_INCREMENT 라
 * {@code OsmGraphLoader load --force} 로 재적재하면 새로 매겨진다. ID 로 저장해두면
 * 재적재 뒤 엉뚱한 엣지를 지우거나 잇게 된다. 그래서 양 끝 좌표를 남기고
 * 기동할 때마다 좌표로 다시 찾는다.
 *
 * <p><b>왜 EDGES 에 직접 안 넣는가</b>: 같은 이유다. {@code load --force} 가 region 의 EDGES 를
 * 통째로 지우므로 손으로 넣은 행이 사라진다. 별도 테이블이라야 살아남는다.
 */
@Getter
@Setter
public class ManualEdgeDTO {

    public static final String ADD = "추가";
    public static final String REMOVE = "삭제";

    /**
     * 스냅 없이 <b>찍은 자리 그대로</b> 노드를 만들어 잇는다.
     *
     * <p>{@link #ADD} 는 20m 안에 엣지가 있으면 거기 정사영해 붙이므로,
     * 도로에서 몇 m 옆 보도 자리를 찍어도 <b>도로 위로 끌려간다</b>.
     * 아직 그려지지 않은 보도를 사람이 직접 놓으려면 그 규칙을 건너뛸 수 있어야 한다.
     *
     * <p>다만 <b>이미 있는 노드가 아주 가까우면(2m) 그것을 다시 쓴다</b> —
     * 안 그러면 연달아 이을 때 같은 자리에 노드가 두 개 생겨 선이 안 이어진다.
     */
    public static final String ADD_EXACT = "추가-고정";

    /**
     * 엣지 없이 <b>노드 하나만</b> 놓는다. {@code fromLat/fromLng} 만 쓰고 {@code to} 는 같은 값이다.
     *
     * <p>찍은 자리 그대로 만든다({@link #ADD_EXACT} 와 같은 규칙). 아무 선에도 안 붙은
     * <b>외톨이 노드</b>라서 <b>길찾기는 이걸 쓰지 못한다</b> — 들어가고 나올 엣지가 없다.
     * 이어 붙이기 전까지는 아무 일도 하지 않는 표식이다.
     *
     * <p>쓰임새: 없는 보도를 그릴 때 <b>꺾이는 자리를 먼저 다 찍어두고</b> 그다음
     * {@link #ADD}(스냅 6m)로 이어 붙인다. 두 점을 한 번에 찍어야 하는 제약이 없어진다.
     */
    public static final String NODE = "노드";

    private long id;
    private String regionId;

    /** {@link #ADD} 또는 {@link #REMOVE}. */
    private String action;

    /** 추가: 이을 한쪽 끝 / 삭제: 지울 엣지의 한쪽 끝. */
    private double fromLat;
    private double fromLng;

    /** 추가: 이을 반대쪽 끝 / 삭제: 지울 엣지의 반대쪽 끝. */
    private double toLat;
    private double toLng;

    /** 추가일 때만. 가중치 표({@code wheelway.highway-weights})의 키. 비어 있으면 {@code crossing}. */
    private String edgeKind;

    /** 왜 넣었는지·뺐는지. 나중에 판단 근거가 된다. */
    private String note;

    private String createdAt;
}

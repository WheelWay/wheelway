package kopo.poly.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * NODES 조회 결과. 그래프를 메모리에 올릴 때 쓰는 최소 형태다.
 *
 * <p>{@code ELEV_M}(DEM 미적용)과 {@code NODE_TYPE}(교차로 판정 미구현)은 지금 전부 NULL 이라 조회하지 않는다.
 * 경사도를 붙이는 다음 단계에서 {@code elevM} 이 여기 추가된다.
 */
@Getter
@Setter
public class NodeDTO {

    /** NODES.ID — 그래프 안에서 노드를 가리키는 실제 키. */
    private long id;

    private double latitude;
    private double longitude;
}

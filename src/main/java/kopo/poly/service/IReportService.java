package kopo.poly.service;

import java.util.List;
import java.util.Set;

import kopo.poly.dto.ObstacleReportDTO;

/** 장애물 제보. 등록·조회와, 그 결과를 차단 Set 에 반영하는 일까지가 이 서비스의 범위다. */
public interface IReportService {

    /**
     * 그 지역의 제보 전체. 낮음을 제외한 각 건에 {@code segments}(막는 보도 구간)를 채워 준다.
     *
     * <p>낮음에 채우지 않는 이유는 차단에 관여하지 않아서다 — 그려주면 화면에서
     * '막히는 구간'처럼 보이는데 실제로는 아무것도 안 막는다.
     */
    List<ObstacleReportDTO> list();

    /**
     * 새 제보를 저장하고, <b>높음이면 그 자리에서 차단에 반영</b>한다.
     *
     * <p>돌아오는 DTO 에는 채번된 {@code id} 와 {@code segments} 가 들어 있다.
     *
     * @throws IllegalArgumentException 정도·유형 값이 목록에 없거나 반경이 상한을 넘을 때
     */
    ObstacleReportDTO register(ObstacleReportDTO dto);

    /**
     * 관리자가 제보의 좌표·반경을 고친다. {@code radiusM} 이 {@code null} 이면 전역 설정값을 쓴다.
     *
     * @return 고친 행 수. 0 이면 그 region 에 그 ID 가 없다
     */
    int updateLocation(long id, double lat, double lng, Double radiusM);

    /**
     * 확정(공개) / 반려 / 해소 처리. 차단 Set 을 다시 계산한다.
     *
     * @return 고친 행 수. 0 이면 그 region 에 그 ID 가 없다
     */
    int updateStatus(long id, String status, String verifiedBy);

    /**
     * '보통' 제보가 걸치는 엣지. 경로 A/B 를 만들 때 <b>경로 B 에서만</b> 빼는 대상이다.
     *
     * <p>{@link IRouteService#searchRoute(double, double, double, double, Set)} 의
     * {@code extraBlocked} 자리에 그대로 넣으라고 만든 것이다. 아직 그 호출을 잇지는 않았다 —
     * 두 안을 화면에 어떻게 보여줄지가 정해지지 않았기 때문이다.
     */
    Set<Long> mediumEdgeIds();
}

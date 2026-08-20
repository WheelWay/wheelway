package kopo.poly.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kopo.poly.dto.BlockedPointDTO;
import kopo.poly.dto.ConstructionZoneDTO;
import kopo.poly.dto.EdgeDTO;
import kopo.poly.dto.ManualEdgeDTO;
import kopo.poly.dto.NodeDTO;

/**
 * 그래프 적재용 조회. 서버 기동 시 한 번만 호출한다(요청마다 재조회 없음).
 *
 * <p>SQL 은 {@code resources/mapper/GraphMapper.xml} 에 있다.
 */
@Mapper
public interface IGraphMapper {

    /**
     * 그래프 노드 전체. 출발/도착 좌표를 붙일 스냅 후보이기도 하다.
     */
    List<NodeDTO> getNodes(@Param("regionId") String regionId);

    /**
     * 탐색에 쓸 엣지만. <b>{@code EXCLUDE_REASON IS NULL} 조건이 계단 하드필터의 실체다.</b>
     *
     * <p>계단({@code exclude_reason='steps'})은 EDGES 에 행으로는 남아 있지만 여기서 걸러진다.
     * 행을 남겨두는 이유는 나중에 "계단 위치를 지도에 표시" 같은 기능을 붙일 때 좌표·연결정보가 필요해서다.
     * {@code overpass}/{@code underpass} 는 판정 로직 미구현이라 아직 아무 행에도 채워져 있지 않다.
     */
    List<EdgeDTO> getRoutableEdges(@Param("regionId") String regionId);

    /**
     * 현재 공사 중인 구간의 좌표. 하드필터 2번.
     *
     * <p>지오코딩 전이라 좌표가 NULL 인 행은 매칭할 수단이 없으므로 제외한다.
     * 카카오 REST 키로 67건을 지오코딩하기 전까지 <b>0건을 돌려주는 것이 정상</b>이다.
     */
    List<BlockedPointDTO> getActiveConstructionPoints(@Param("regionId") String regionId);

    /**
     * 완전 차단 제보. 하드필터 3번.
     *
     * <p>'대기'도 포함하는 이유는 <b>높음 제보를 등록 즉시 차단에 반영</b>하고
     * 관리자가 사후에 확정/반려하기로 했기 때문이다.
     */
    List<BlockedPointDTO> getBlockingReportPoints(@Param("regionId") String regionId);

    /**
     * 화면에 찍을 공사구간. 공사명·기간을 함께 준다.
     *
     * <p>{@link #getActiveConstructionPoints} 와 조건은 같지만 용도가 다르다.
     * 그쪽은 차단 Set 을 만들기 위한 좌표만, 이쪽은 사용자에게 보여줄 정보까지 가져온다.
     */
    List<ConstructionZoneDTO> getConstructionZonesForDisplay(@Param("regionId") String regionId);

    /**
     * 계단 구간의 좌표열({@code GEOMETRY_JSON}).
     *
     * <p><b>계단 행을 EDGES 에 남겨둔 이유가 바로 이 기능이다.</b> 그래프에서는 빠져 있지만
     * "여기는 계단이라 못 갑니다"를 지도에 보여주려면 좌표가 DB 에 있어야 한다.
     *
     * <p>같은 구간의 반대 방향 행이 좌표만 뒤집힌 중복이므로 한쪽만 가져온다.
     */
    List<String> getStepsGeometry(@Param("regionId") String regionId);

    /**
     * 공사구간의 좌표와 차단 반경을 수정한다.
     *
     * <p>지오코딩이 필지·교차로 중심에 찍은 좌표를 사람이 지도에서 보고 바로잡는 용도다.
     * {@code blockRadiusM} 에 {@code null} 을 주면 전역 설정값을 쓰겠다는 뜻이다.
     *
     * @return 수정된 행 수. 0 이면 해당 ID 가 그 region 에 없다는 뜻이다
     */
    int updateConstructionZone(@Param("id") long id,
                               @Param("regionId") String regionId,
                               @Param("latitude") double latitude,
                               @Param("longitude") double longitude,
                               @Param("blockRadiusM") Double blockRadiusM);

    /**
     * 표시 전용 기준선을 저장한다. {@code null} 이면 지운다.
     *
     * <p><b>좌표·반경 수정과 일부러 분리했다.</b> 이 값은 차단 계산에 들어가지 않으므로
     * 저장해도 차단 Set 을 다시 계산할 필요가 없다. 한 메서드로 묶으면 '선만 그렸는데
     * 차단이 바뀌었나?' 하는 오해가 생기고, 실제로 재계산을 부르게 되어 낭비다.
     *
     * @return 수정된 행 수. 0 이면 해당 ID 가 그 region 에 없다는 뜻이다
     */
    int updateConstructionNoteLine(@Param("id") long id,
                                   @Param("regionId") String regionId,
                                   @Param("noteLineJson") String noteLineJson);

    /**
     * 사람이 직접 넣고 뺀 엣지 목록. 기동 시 그래프에 반영하고, 화면에도 그대로 보여준다.
     *
     * <p>좌표로 저장돼 있다 — {@code EDGES.ID} 는 재적재하면 새로 매겨져 못 믿는다.
     */
    List<ManualEdgeDTO> getManualEdges(@Param("regionId") String regionId);

    int insertManualEdge(ManualEdgeDTO dto);

    /** @return 지운 행 수. 0 이면 그 region 에 해당 ID 가 없다는 뜻이다 */
    int deleteManualEdge(@Param("id") long id, @Param("regionId") String regionId);
}

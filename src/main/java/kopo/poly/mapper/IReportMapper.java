package kopo.poly.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kopo.poly.dto.ObstacleReportDTO;

/**
 * 장애물 제보 CRUD. SQL 은 {@code resources/mapper/ReportMapper.xml} 에 있다.
 *
 * <p><b>{@link IGraphMapper#getBlockingReportPoints} 와 역할이 다르다.</b>
 * 그쪽은 그래프를 만들 때 쓸 '차단 좌표'만 뽑는 조회고(하드필터 3번), 이쪽은
 * 화면에 보여주고 사람이 고치는 쪽이다. 같은 테이블을 보지만 섞으면 서로의 조건이 오염된다 —
 * 예컨대 이쪽은 낮음·보통·반려까지 전부 가져와야 하지만 그쪽은 절대 가져오면 안 된다.
 */
@Mapper
public interface IReportMapper {

    /**
     * 그 지역의 제보 전체. <b>정도·상태를 가리지 않는다</b> — 관리자 화면이 반려된 것까지
     * 다 봐야 하기 때문이다. 걸러내는 것은 화면 쪽 일이다.
     */
    List<ObstacleReportDTO> getReports(@Param("regionId") String regionId);

    /** 한 건. 상태를 바꾸기 전에 정도·좌표를 확인해야 해서 필요하다. */
    ObstacleReportDTO getReport(@Param("id") long id, @Param("regionId") String regionId);

    /**
     * 새 제보. {@code id} 가 채워져 돌아온다.
     *
     * <p>{@code EDGE_ID} 는 컬럼에서 아예 빼고 INSERT 한다 — 항상 NULL 이어야 하는 값이라
     * 실수로 채워 넣을 여지를 문법 차원에서 없애는 편이 낫다. 이유는 {@link ObstacleReportDTO} 참고.
     */
    int insertReport(ObstacleReportDTO dto);

    /**
     * 관리자가 좌표·반경을 고친다. {@code blockRadiusM} 에 {@code null} 을 주면 전역 설정값을 쓴다는 뜻이다.
     *
     * @return 고친 행 수. 0 이면 그 region 에 해당 ID 가 없다는 뜻이다
     */
    int updateReportLocation(@Param("id") long id,
                             @Param("regionId") String regionId,
                             @Param("latitude") double latitude,
                             @Param("longitude") double longitude,
                             @Param("blockRadiusM") Double blockRadiusM);

    /**
     * 확정(공개) / 반려 / 해소 처리.
     *
     * <p>{@code VERIFIED_AT} 은 애플리케이션이 아니라 SQL 의 {@code NOW()} 로 채운다.
     * 이 값은 '서버가 언제 처리했나'라서 DB 시계 하나로 통일하는 편이 맞다.
     *
     * @return 고친 행 수
     */
    int updateReportStatus(@Param("id") long id,
                           @Param("regionId") String regionId,
                           @Param("status") String status,
                           @Param("verifiedBy") String verifiedBy);
}

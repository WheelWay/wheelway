package kopo.poly.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kopo.poly.dto.LowFloorSeenDTO;

/**
 * 저상차 관측 누적. SQL 은 {@code resources/mapper/BusSeenMapper.xml} 에 있다.
 *
 * <p><b>표가 없어도 앱은 떠야 한다.</b> 이 표는 있으면 좋은 것이지 없으면 안 되는 것이 아니다 —
 * 없으면 예전처럼 메모리에만 쌓고 재기동 때마다 다시 채운다. 그래서 부르는 쪽
 * ({@code BusService})이 예외를 잡고 한 번만 알린 뒤 조용히 넘어간다.
 */
@Mapper
public interface IBusSeenMapper {

    /** 이 지역에서 지금까지 쌓인 관측 전부. 기동할 때 한 번 읽는다. */
    List<LowFloorSeenDTO> getSeen(@Param("regionId") String regionId);

    /**
     * 한 노선의 관측을 덮어쓴다.
     *
     * <p><b>더하지 않고 덮어쓴다.</b> 메모리 쪽이 늘 원본이고(기동할 때 DB 에서 읽어 채운 뒤
     * 거기에 더해 간다) 여기 쓰는 값은 그 사본이다. DB 에서 더하면 같은 관측이 두 번 세어진다.
     */
    void upsertSeen(@Param("seen") LowFloorSeenDTO seen);
}

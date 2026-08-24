package kopo.poly.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kopo.poly.dto.RouteSpeedDTO;

/**
 * 노선별·시간대별 주행 속도 관측 누적. SQL 은 {@code resources/mapper/BusSpeedMapper.xml} 에 있다.
 *
 * <p><b>표가 없어도 앱은 떠야 한다.</b> {@link IBusSeenMapper} 와 같은 규칙이다 —
 * 없으면 예전처럼 메모리에만 쌓고 재기동 때마다 다시 채운다. 그래서 부르는 쪽
 * ({@code BusService})이 예외를 잡고 한 번만 알린 뒤 조용히 넘어간다.
 *
 * <p><b>저상 관측과 표를 따로 두는 이유</b>: 답하는 물음이 다르다. 저상 관측은
 * '이 노선에 휠체어로 탈 수 있는 차가 다니는가'이고 노선당 한 줄이면 된다.
 * 이쪽은 '그 노선이 이 시간대에 얼마나 빨리 가는가'라서 노선당 시간대 수만큼 줄이 생긴다.
 * 한 표에 밀어 넣으면 열쇠도 갱신 주기도 어긋난다.
 */
@Mapper
public interface IBusSpeedMapper {

    /** 이 지역에서 지금까지 쌓인 관측 전부. 기동할 때 한 번 읽는다. */
    List<RouteSpeedDTO> getSpeeds(@Param("regionId") String regionId);

    /**
     * 한 노선의 한 시간대를 덮어쓴다.
     *
     * <p><b>더하지 않고 덮어쓴다.</b> {@link IBusSeenMapper#upsertSeen} 과 같은 이유다 —
     * 메모리 쪽이 늘 원본이고(기동할 때 DB 에서 읽어 채운 뒤 거기에 더해 간다)
     * 여기 쓰는 값은 그 사본이다. DB 에서 더하면 같은 관측이 두 번 세어진다.
     */
    void upsertSpeed(@Param("speed") RouteSpeedDTO speed);
}

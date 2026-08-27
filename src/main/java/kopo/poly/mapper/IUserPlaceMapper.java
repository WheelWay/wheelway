package kopo.poly.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kopo.poly.dto.UserPlaceDTO;

/**
 * 사용자가 등록한 장소. SQL 은 {@code resources/mapper/UserPlaceMapper.xml} 에 있다.
 *
 * <p><b>모든 메서드에 {@code username} 이 들어간다.</b> ID 만으로 다루면 남의 장소를
 * 읽거나 지울 수 있다. {@code IWalkMapper} 와 같은 방침이다.
 */
@Mapper
public interface IUserPlaceMapper {

    /** 그 사람이 등록한 장소 전부. 집 → 회사 → 즐겨찾기 순으로 나온다. */
    List<UserPlaceDTO> getPlaces(@Param("username") String username);

    /**
     * 한 종류만. 집 버튼이 눌렸을 때 쓴다.
     *
     * @param placeType {@code HOME} / {@code WORK} / {@code FAV}
     * @return 없으면 {@code null}. 여러 개면 가장 최근에 등록한 것
     */
    UserPlaceDTO getPlace(@Param("username") String username,
                          @Param("placeType") String placeType);

    /**
     * 등록하거나, 이미 있으면 덮어쓴다.
     *
     * <p><b>왜 UPSERT 인가</b>: 집은 사용자당 하나다. 화면에서 집을 다시 등록하는 것은
     * '두 번째 집을 만드는 것'이 아니라 '이사한 것'이다. INSERT 로만 두면
     * {@code UQ_USER_PLACES} 에 걸려서 사용자가 먼저 지워야 고칠 수 있다.
     *
     * <p>{@code ID} 는 건드리지 않는다 — 나중에 다른 표가 장소를 가리키게 되면
     * 주소를 고칠 때마다 그 참조가 끊긴다.
     *
     * @return 채번된 {@code id} 가 dto 에 담긴다
     */
    int upsertPlace(UserPlaceDTO dto);

    /**
     * 등록을 지운다. 되돌릴 수 없다.
     *
     * <p>{@code WALK_RECORDS} 처럼 '빼기' 를 두지 않는다. 이동 기록은 왜 뺐는지가 남아야 할
     * 관측값이지만, 잘못 등록한 집 주소는 남겨둘 이유가 없다.
     *
     * @return 지운 행 수. 0 이면 없는 ID 이거나 남의 장소다
     */
    int deletePlace(@Param("id") long id, @Param("username") String username);
}

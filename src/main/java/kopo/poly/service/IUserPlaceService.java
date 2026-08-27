package kopo.poly.service;

import java.util.List;

import kopo.poly.dto.UserPlaceDTO;

/**
 * 사용자가 등록해둔 장소.
 *
 * <p>이 서비스가 하는 일은 <b>주소를 좌표로 굳혀서 넣는 것</b>이다. 화면은 주소만 보내고,
 * 좌표로 바꾸는 것도 그 좌표가 안내 가능한 곳인지 보는 것도 여기서 한다.
 *
 * <p><b>왜 좌표를 저장 시점에 굳히는가</b>: 주소만 저장하면 집 버튼을 누를 때마다 카카오를
 * 부르게 된다. 느리고, 한도를 먹고, 카카오가 잠깐 죽으면 집 버튼이 같이 죽는다.
 * 등록은 어쩌다 한 번이고 사용은 매일이다.
 */
public interface IUserPlaceService {

    /**
     * 그 사람이 등록한 장소 전부. 집 → 회사 → 즐겨찾기 순.
     *
     * <p>지금 안내 중인 지역 밖의 장소도 <b>빼지 않고 준다.</b>
     * {@code usable=false} 로 표시만 한다 — 안 보여주면 사용자는 등록이 지워진 줄 안다.
     */
    List<UserPlaceDTO> list(String username);

    /** 한 종류만. 없으면 {@code null}. */
    UserPlaceDTO get(String username, String placeType);

    /**
     * 등록하거나 덮어쓴다. 주소를 좌표로 바꾸는 것이 여기서 일어난다.
     *
     * <p>이미 같은 종류가 있으면 <b>덮어쓴다.</b> 집을 다시 등록하는 것은
     * '두 번째 집'이 아니라 '이사'다.
     *
     * @param placeType     {@code HOME} / {@code WORK} / {@code FAV}
     * @param address       {@code data.address} 원문. <b>우편번호나 상세주소를 붙여 보내지 말 것</b>
     * @param addressDetail 동·호수. 없어도 된다. 좌표에 영향이 없다
     * @param zonecode      우편번호. 표시용이라 없어도 된다
     * @param label         화면에 뜰 이름. 비면 종류에 따라 '집'·'회사'가 들어간다.
     *                      {@code FAV} 는 반드시 있어야 한다 — 이름이 없으면 여러 개를 구분할 수 없다
     *
     * @throws IllegalArgumentException 값이 비었거나, 주소를 못 찾았거나,
     *         찾은 좌표가 지금 안내 중인 지역 밖일 때. {@code message} 는 그대로 화면에 뜬다
     * @throws kopo.poly.geocode.GeocodeUnavailableException 주소 검색 자체가 안 될 때.
     *         '그런 주소가 없다' 와 구분해야 한다 — 고칠 사람이 사용자가 아니라 우리다
     */
    UserPlaceDTO save(String username, String placeType, String address,
                      String addressDetail, String zonecode, String label);

    /**
     * 등록을 지운다. 되돌릴 수 없다.
     *
     * @return 지운 행 수. 0 이면 없는 ID 이거나 남의 장소다
     */
    int delete(long id, String username);
}

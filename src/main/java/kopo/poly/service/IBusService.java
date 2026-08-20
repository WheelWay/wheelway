package kopo.poly.service;

import java.util.List;

import kopo.poly.dto.BusArrivalDTO;
import kopo.poly.dto.BusRouteDTO;
import kopo.poly.dto.BusStopDTO;

/**
 * 버스 정류장과 저상버스 도착 안내.
 *
 * <p><b>제공자를 고르는 것은 여기까지다.</b> 위쪽은 지금 지역이 서울인지 보은인지 모른다 —
 * {@code region-id} 한 줄을 바꾸면 지도와 버스가 같이 따라온다.
 */
public interface IBusService {

    /** 지금 지역이 쓰는 제공자 이름({@code tago} · {@code topis}). 화면 안내에 쓴다. */
    String providerName();

    /** 이 좌표에서 가까운 정류장. 개수는 {@code wheelway.bus-nearby-limit} 가 정한다. */
    List<BusStopDTO> nearbyStops(double lat, double lng);

    /**
     * 지도 화면 안에 있는 정류장.
     *
     * <p>근접 조회(8곳)로는 지도를 채울 수 없어 따로 둔다. 지역 전체 목록을 한 번 받아
     * 캐시해 두고 여기서 걸러 준다 — 지도를 옮길 때마다 API 를 부르지 않는다.
     *
     * @param limit 너무 넓게 보면 수백 개가 되므로 상한을 둔다.
     *              넘치면 <b>화면 한가운데에 가까운 것부터</b> 남긴다
     */
    List<BusStopDTO> stopsInBounds(double minLat, double minLng,
                                   double maxLat, double maxLng, int limit);

    /**
     * 이름으로 정류장을 찾는다. 장소 검색에 얹으려고 둔다.
     *
     * <p><b>왜 필요한가</b>: 카카오 로컬 API 에는 버스정류장 카테고리가 아예 없다
     * (대중교통은 {@code SW8 지하철역} 하나뿐이다). 지도 타일에 보이는 정류장 아이콘은
     * 카카오가 그려 넣은 그림이라 좌표도 이름도 꺼낼 수 없다. 그래서 정류장 이름을
     * 그대로 쳐도 검색에 <b>한 건도 안 나온다</b> — 우리가 받아둔 목록으로 채워야 한다.
     *
     * <p>지역 전체 목록을 이미 캐시하고 있으므로({@code stopsInBounds} 와 같은 것)
     * 이 검색은 API 를 부르지 않는다.
     *
     * @param q     찾을 말. 이름에 들어 있으면 걸린다
     * @param near  가까운 순으로 세울 기준 좌표. {@code null} 이면 이름순
     * @param limit 몇 개까지
     */
    List<BusStopDTO> findStops(String q, double[] near, int limit);

    /**
     * <b>걸어가서 탈 수 있는 정류장</b>을 가까운 순으로 찾는다.
     *
     * <p><b>왜 '가까운 정류장'과 다른가</b>: 가까운 정류장에 저상버스가 안 서면
     * 휠체어 사용자에게는 없는 정류장이나 마찬가지다. 실제로 충북생명산업고등학교
     * 옆 182m 에 정류장이 있는데, 저상 노선이 안 서서 사용자가 1,858m 떨어진
     * 보은고등학교까지 걸어야 했다 — 그걸 사람이 직접 찾아야 했던 것이 문제였다.
     *
     * <p><b>어떻게 싸게 구하나</b>: 정류장마다 '여기 저상이 서나' 를 물으면 후보 수만큼
     * 호출이 나가고, 몰아치면 빈 응답이 온다. 대신 <b>저상 노선의 경유 정류장</b>을
     * 받는다 — 보은은 저상이 5개 노선뿐이라 호출 5번이면 저상이 서는 정류장 전부를 안다.
     * 그 뒤로는 계산만 하면 된다.
     *
     * @param limit 몇 곳까지. 화면은 몇 개만 보여주면 된다
     * @return 가까운 순. 저상 노선이 하나도 없는 지역이면 빈 목록
     */
    List<BoardingStop> boardingStops(double lat, double lng, int limit);

    /**
     * 걸어가서 탈 수 있는 정류장 한 곳.
     *
     * @param stop     정류장
     * @param routeNos 여기 서는 <b>저상</b> 노선 번호들. 이게 이 정류장을 고른 이유라
     *                 화면에 같이 보여줘야 한다 — '왜 더 먼 데로 가라는지'가 여기 있다
     */
    record BoardingStop(BusStopDTO stop, List<String> routeNos) { }

    /**
     * <b>여기서 타서 저기서 내릴 수 있는 저상 노선</b>을 찾는다. 복합 경로의 뼈대다.
     *
     * <p>후보를 <b>양쪽 다 여러 곳</b> 받는 이유: 어느 정류장에서 타야 하는지는 노선이 정한다.
     * 출발지에서 가장 가까운 정류장과 목적지에서 가장 가까운 정류장을 잇는 노선이 없는 일이
     * 흔하고, 그때 두 번째로 가까운 정류장끼리는 한 번에 이어지는 경우가 많다.
     * 한 곳씩만 보면 '직통이 없다'는 거짓말을 하게 된다.
     *
     * <p><b>방향을 순번으로 가린다</b>({@code ord(승차) < ord(하차)}). 같은 이름의 정류장이
     * 상·하행 두 곳이라, 순번을 안 보면 반대편에서 안 오는 버스를 기다리게 된다.
     *
     * <p><b>저상 노선만 본다.</b> 휠체어로 못 타는 버스로 짠 경로는 경로가 아니다.
     * 판별 근거는 {@code boardingStops} 와 같다(시간표 → 도착정보 관측).
     *
     * <p>걸리는 시간은 담아 주지만 <b>기다리는 시간은 여기 없다</b> — 그건 정류장의 성질이고
     * 지금 몇 시인가에 달렸다. 부르는 쪽이 도착정보·시간표로 따로 붙인다.
     *
     * @param fromStopIds 탈 만한 정류장 후보
     * @param toStopIds   내릴 만한 정류장 후보
     * @param limit       몇 개까지. 후보 5×5 면 조합이 금세 수십 개가 된다
     * @return 없으면 빈 목록. <b>직통이 없다는 뜻이고, 환승은 아직 다루지 않는다</b>
     */
    List<kopo.poly.dto.BusLinkDTO> directLinks(java.util.Collection<String> fromStopIds,
                                               java.util.Collection<String> toStopIds,
                                               int limit);

    /** 정류장 한 곳의 도착 현황. */
    Board boardAt(String stopId);

    /**
     * 정류장 한 곳에서 보여줄 것 전부.
     *
     * <p>도착정보와 경유노선을 <b>같이</b> 주는 이유: 군 단위는 배차가 드물어
     * 도착정보가 0건인 시간대가 대부분이다. 그때 화면이 비면 사용자는 고장으로 읽는다.
     * '지금 오는 버스는 없지만 이 정류장에는 8개 노선이 선다'까지는 말해줄 수 있어야 한다.
     *
     * @param arrivals 곧 도착할 버스. 가까운 순
     * @param routes   이 정류장을 지나는 노선. <b>저상 여부는 없다</b> — 노선 단위로는 알 수 없다
     */
    record Board(List<BusArrivalDTO> arrivals, List<BusRouteDTO> routes) { }
}

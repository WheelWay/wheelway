package kopo.poly.bus;

import java.util.List;

import kopo.poly.dto.BusArrivalDTO;
import kopo.poly.dto.BusRouteDTO;
import kopo.poly.dto.BusStopDTO;

/**
 * 버스 정보 제공자 한 곳. <b>지역마다 제공자가 다르다.</b>
 *
 * <p>전국 하나로 되는 API 가 없다는 것을 실제 호출로 확인했다 —
 * TAGO 도시코드 138건에 서울이 없고(서울은 TOPIS 가 따로 있다),
 * {@code cityCode=11} 을 억지로 넣어도 {@code totalCount=0} 인 빈 응답이 온다.
 *
 * <p>그래서 클라이언트는 두 벌이 되는 것을 받아들이고, 대신 <b>돌려주는 모양을 여기서 고정</b>한다.
 * 위쪽(서비스·컨트롤러·화면)은 제공자가 누구인지 몰라도 된다.
 *
 * <p>구현체는 실패를 감추지 않는다. 키가 막혔거나 응답이 오류면
 * {@link BusUnavailableException} 을 던진다 — 빈 목록으로 돌려주면 화면이
 * '이 근처에 정류장이 없다'고 잘못 말한다. 둘은 전혀 다른 상황이다.
 */
public interface IBusClient {

    /** 로그와 화면 안내에 찍을 제공자 이름({@code tago} · {@code topis}). */
    String providerName();

    /**
     * 이 좌표에서 가까운 정류장. <b>가까운 순</b>으로, 최대 {@code limit} 개.
     *
     * <p>같은 이름이 여러 건 나오는 것은 정상이다 — 방향별로 정류장이 따로 있다.
     * 어느 쪽에서 타야 하는지는 노선을 봐야 알 수 있어서 합치지 않고 그대로 준다.
     */
    List<BusStopDTO> nearbyStops(double lat, double lng, int limit);

    /**
     * 그 정류장에 곧 도착할 버스들. <b>비어 있는 것이 정상적인 답</b>일 수 있다 —
     * 군 단위는 배차가 드물어 지금 오는 버스가 없는 시간대가 대부분이다.
     */
    List<BusArrivalDTO> arrivals(String stopId);

    /**
     * 그 정류장을 지나는 노선 목록.
     *
     * <p>도착정보가 0건일 때 화면이 비지 않게 하려고 같이 부른다.
     * 저상 여부는 여기서 알 수 없다 — 한 노선에 저상차와 일반차가 섞여 다닌다.
     */
    List<BusRouteDTO> routesAt(String stopId);

    /**
     * 이 지역 <b>전체</b> 노선과 각 노선의 첫차·막차.
     *
     * <p>정류장별 조회가 아니라 지역 통째로 받는다. TAGO 는 노선 목록에 운행시간을 같이 실어주므로
     * 보은 85개 노선이 <b>호출 한 번</b>에 끝난다. 노선마다 따로 물으면 85번이고,
     * 몰아서 부르면 빈 응답이 온다(실측).
     *
     * <p>거의 안 바뀌는 값이라 부르는 쪽에서 캐시해 쓴다.
     *
     * @return {@code routeId → 노선}. {@code running} 은 여기서 채우지 않는다(부르는 쪽 몫)
     */
    java.util.Map<String, BusRouteDTO> allRoutes();

    /**
     * 노선 하나가 지나는 정류장을 <b>순번대로</b> 준다.
     *
     * <p>지금까지 받아 온 것은 전부 정류장 기준이라 '이 정류장에 340번이 선다'까지만 알았다.
     * 이건 방향이 반대다 — '340번이 어디어디를 지나간다'를 준다.
     *
     * <p><b>노선당 호출 한 번</b>이다. 보은 85개 노선이면 85회라, 부르는 쪽이
     * 간격을 두고 캐시해야 한다 — 몰아치면 오류가 아니라 <b>빈 응답</b>이 온다.
     */
    java.util.List<kopo.poly.dto.RouteStopDTO> routeStops(String routeId);

    /**
     * 이 지역 <b>전체</b> 정류장.
     *
     * <p>{@link #nearbyStops} 와 쓰임이 다르다. 근접 조회는 반경이 API 안에 박혀 있어
     * <b>8곳까지만</b> 주므로(numOfRows 를 200 으로 올려도 8이다) 지도에 두루 찍을 수 없다.
     * 지도는 화면에 보이는 범위를 그려야 하니 목록을 통째로 받아 두고 걸러 쓴다.
     *
     * <p>보은은 838곳이 <b>호출 한 번</b>(페이지 나눔 포함)에 들어온다.
     * 정류장 위치는 몇 년째 그대로라 부르는 쪽에서 캐시한다.
     *
     * @return 거리({@code distanceM})는 계산할 기준이 없어 {@code 0} 이다
     */
    List<BusStopDTO> allStops();
}

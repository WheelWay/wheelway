package kopo.poly.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import kopo.poly.bus.BusTimetable;
import kopo.poly.bus.BusUnavailableException;
import kopo.poly.bus.IBusClient;
import kopo.poly.bus.TagoBusClient;
import kopo.poly.dto.BusArrivalDTO;
import kopo.poly.dto.BusRouteDTO;
import kopo.poly.dto.BusStopDTO;
import kopo.poly.dto.BusTimetableDTO;
import kopo.poly.dto.RouteStopDTO;
import kopo.poly.service.IBusService;
import lombok.extern.slf4j.Slf4j;

/**
 * 지금 지역에 맞는 버스 제공자를 골라 부른다.
 *
 * <p><b>왜 지역이 제공자를 고르는가</b>: 전국 하나로 되는 API 가 없다.
 * 서울은 TOPIS({@code ws.bus.go.kr}), 그 밖은 TAGO({@code apis.data.go.kr}) 다.
 * 이걸 화면이나 컨트롤러가 알게 하면 지역을 갈아탈 때마다 여러 군데를 고쳐야 하고,
 * 한 군데를 빠뜨리면 <b>지도는 보은인데 버스는 서울을 부르는</b> 상태가 조용히 생긴다.
 * 그래서 {@code wheelway.bus-sources} 표 하나로 묶고, 고르는 일은 여기서만 한다.
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class BusService implements IBusService {

    /**
     * 저상 관측을 살려 두는 표. <b>없어도 된다.</b>
     *
     * <p>표가 없는 환경에서도 앱은 떠야 하므로, 부르는 자리마다 예외를 잡고
     * 한 번만 알린 뒤 메모리에만 쌓는 예전 동작으로 돌아간다.
     */
    private final kopo.poly.mapper.IBusSeenMapper seenMapper;

    /** 지도가 보고 있는 지역. 버스 제공자도 이 값으로 고른다. */
    @Value("${wheelway.region-id}")
    private String regionId;

    /** {@code region-id → '<제공자>:<도시코드>'}. */
    @Value("#{${wheelway.bus-sources}}")
    private Map<String, String> busSources;

    /**
     * {@code region-id → 시간표 CSV 경로}. 없는 지역은 시간표 없이 돈다.
     *
     * <p>버스 출처와 <b>같은 방식으로 고르는 이유</b>: '{@code region-id} 한 줄만 바꾸면
     * 지역을 갈아탄다'는 규칙을 시간표도 따라야 한다. 따로 두면 지도는 보은인데
     * 시간표는 다른 지역인 상태가 조용히 생기고, 그때 화면은 엉뚱한 시각을 자신 있게 말한다.
     */
    @Value("#{${wheelway.timetable-sources:{:}}}")
    private Map<String, String> timetableSources;

    @Value("${wheelway.bus-nearby-limit:8}")
    private int nearbyLimit;

    /**
     * 시간표도 관측도 없을 때 쓸 버스 주행 속도(km/h). <b>맨 마지막 차선책이다.</b>
     *
     * <p>정차·신호가 다 들어간 표정속도라 사람이 생각하는 '버스 속도'보다 훨씬 느리다.
     * 이 값으로 낸 시간에는 화면이 반드시 '어림'이라고 밝혀야 한다 —
     * 근거 없는 숫자를 근거 있는 숫자와 같은 얼굴로 내보내면 안 된다.
     */
    @Value("${wheelway.bus-speed-kmh:19}")
    private double busSpeedKmh;

    @Value("${wheelway.bus-stop-cache-min:30}")
    private long stopCacheMin;

    /** 도착정보를 몇 초 동안 재사용할지. 정류장 캐시(분 단위)와 전혀 다른 물건이다. */
    @Value("${wheelway.bus-board-cache-sec:12}")
    private long boardCacheSec;

    /**
     * 정류장별 경유노선을 몇 분 동안 재사용할지.
     *
     * <p>어느 노선이 어느 정류장에 서는지는 개편이 있을 때나 바뀐다. 길게 잡아도 된다.
     * 길게 잡아야 하는 이유가 따로 있다 — 아래 {@link #routesCache} 참고.
     */
    @Value("${wheelway.bus-routes-cache-min:360}")
    private long routesCacheMin;

    /**
     * 공공데이터포털 서비스키. <b>계정당 하나</b>라 TAGO 4종·서울 4종이 같은 키를 쓴다.
     *
     * <p>이름이 {@code tago.} 로 남아 있는데 실제로는 포털 공용 키다.
     * {@code datagokr.service-key} 로 옮기는 중이라 둘 다 본다 — 옮기는 동안
     * 어느 이름으로 적어도 동작한다.
     */
    @Value("${datagokr.service-key:${tago.service-key:}}")
    private String serviceKey;

    /** 지금 지역의 제공자. 기동할 때 한 번 만든다. 없을 수 있다(서울처럼 아직 구현이 없는 경우). */
    private IBusClient client;

    /** 제공자를 못 만든 이유. 화면에 그대로 보여줘서 '고장'과 '아직 안 열림'을 구분하게 한다. */
    private String unavailableReason;

    /**
     * 근접정류소 응답 캐시.
     *
     * <p>정류장 위치는 몇 년째 그대로인데 개발계정은 하루 호출 수가 정해져 있다.
     * 지도를 조금씩 움직일 때마다 새로 부르면 그 한도를 금방 쓴다.
     *
     * <p><b>도착정보는 절대 캐시하지 않는다</b> — 그건 '지금 몇 분 뒤'라서
     * 30초만 묵어도 틀린 값이 된다.
     */
    private final Map<String, Cached> stopCache = new ConcurrentHashMap<>();

    private record Cached(long atMs, List<BusStopDTO> stops) { }

    /**
     * 도착정보 캐시. <b>정류장 캐시와 목적이 정반대다 — 헷갈리지 말 것.</b>
     *
     * <pre>
     *   정류장 캐시  30분  안 바뀌는 값을 다시 안 받으려고
     *   도착 캐시    12초  같은 순간에 몰린 요청을 한 번으로 묶으려고
     * </pre>
     *
     * <p>같은 정류장을 100명이 보고 있으면 화면 갱신 주기(45초)마다 TAGO 호출이 100번 나간다.
     * 카카오가 이 문제를 안 겪는 것은 중앙에서 한 번 받아 모두에게 뿌리기 때문이고,
     * 여기가 그 자리다.
     *
     * <p><b>응답을 통째로 담는다.</b> 저상버스만 골라 담으면 호출은 그대로면서
     * '전체 4대 중 저상 2대'를 못 말하게 되고, 화면에서 [저상만] 을 끌 때 다시 불러야 한다.
     * TAGO 가 애초에 '저상만 달라'를 못 받아서 응답에는 늘 전부 들어 있다.
     *
     * <p>여기서는 <b>빈 결과도 담는다</b> — 정류장 캐시와 다른 점이다. 배차가 드문 곳은
     * 도착 0건이 정상적인 답이고, 12초라 잘못 담혀도 곧 사라진다. 30분짜리였다면
     * 빈 값 하나가 그 자리를 오래 망가뜨렸을 것이다(실제로 겪었다).
     */
    private final Map<String, CachedBoard> boardCache = new ConcurrentHashMap<>();

    private record CachedBoard(long atMs, Board board) { }

    /**
     * 지역 전체 노선의 첫차·막차. {@code routeId → 노선}.
     *
     * <p><b>기동할 때 받지 않고 처음 쓸 때 받는다.</b> 기동을 TAGO 상태에 묶으면
     * 버스와 무관한 화면까지 같이 늦어지고, TAGO 가 죽어 있을 때 앱이 안 뜰 수 있다.
     * 첫 요청 한 번만 1초쯤 더 걸리고 그 뒤로는 호출이 0 이다.
     *
     * <p>보은 85개 노선이 <b>호출 한 번</b>에 들어온다 — TAGO 가 노선 목록에
     * 운행시간을 같이 실어주기 때문이다. 노선마다 따로 물으면 85번이고 그러면 빈 응답이 온다.
     */
    private volatile Map<String, BusRouteDTO> routeHours = null;
    private volatile long routeHoursAtMs = 0;

    /** 운행시간은 개편이 있을 때나 바뀐다. 하루 한 번이면 넉넉하다. */
    private static final long ROUTE_HOURS_TTL_MS = 24 * 60 * 60 * 1000L;

    /**
     * 지역 전체 정류장. 지도에 두루 찍으려면 이게 있어야 한다.
     *
     * <p>근접 조회는 반경이 API 안에 박혀 있어 <b>8곳까지만</b> 준다 —
     * numOfRows 를 200 으로 올려도 8이다. 그걸로는 지도를 채울 수 없다.
     *
     * <p>보은 838곳. 정류장 위치는 몇 년째 그대로라 하루 한 번이면 넉넉하다.
     */
    private volatile List<BusStopDTO> allStops = null;
    private volatile long allStopsAtMs = 0;

    /**
     * 정류장별 경유노선 캐시. {@code stopId → 노선 목록}.
     *
     * <p><b>왜 새로 생겼나</b>: 전에는 도착정보가 0건일 때만 경유노선을 불렀다.
     * 호출을 아끼려던 것인데, 시간표가 붙으면서 그 조건이 틀린 것이 됐다 —
     * <b>보은 저상 노선은 도착정보에 절대 안 나온다.</b> 그래서 다른 노선 한 대가
     * 도착 중이라는 이유로 경유노선을 건너뛰면, 정작 휠체어로 탈 수 있는
     * 340번의 '다음 12:55' 가 화면에서 통째로 사라진다.
     *
     * <p>그래서 <b>늘 부르되 오래 캐시한다.</b> 어느 노선이 어느 정류장에 서는지는
     * 개편이 있을 때나 바뀌므로, 처음 한 번만 호출이고 그 뒤로는 0이다.
     *
     * <p>덤으로 얻는 것: TAGO 의 경유노선 응답은 부를 때마다 흔들린다
     * (같은 정류장에서 23 → 22 → 18 → 0). 처음 받은 <b>비어 있지 않은</b> 답을
     * 붙잡아 두면 그 흔들림이 화면까지 오지 않는다.
     */
    private final Map<String, CachedRoutes> routesCache = new ConcurrentHashMap<>();

    /**
     * 노선별 경유 정류장. {@code routeId → 순번대로 세운 정류장}.
     *
     * <p>이게 있어야 <b>'버스가 이 정류장까지 오는 데 몇 분'</b> 을 말할 수 있다.
     * 없으면 기점 출발 시각과 편도 소요시간만 아는 상태라, 화면이 할 수 있는 말이
     * '11:40 ~ 12:25 사이 어딘가' 뿐이다(45분짜리 구간은 나갈 시각을 못 정한다).
     *
     * <p><b>★ 노선당 호출 한 번이라 한꺼번에 받으면 안 된다.</b> 정류장 하나에 30개 노선이
     * 서는데 그걸 다 부르면 30회가 연달아 나가고, 그러면 오류가 아니라 <b>빈 응답</b>이 온다
     * (250ms 간격 30여 회로 실제로 겪었다). 그래서 두 가지로 막는다.
     * <pre>
     *   ① 저상 노선만 받는다     탈 수 있는 버스의 시각만 있으면 된다. 보은은 5개뿐이다
     *   ② 한 요청에 최대 2개     모자란 것은 다음 갱신(45초 뒤)에 채워진다
     * </pre>
     * 경유 정류장은 개편 때나 바뀌므로 한 번 받으면 하루는 그대로 쓴다.
     */
    private final Map<String, List<RouteStopDTO>> routeStopsCache = new ConcurrentHashMap<>();

    /** 한 요청에서 새로 받을 노선 수 상한. 위 ② 참고. */
    private static final int ROUTE_STOPS_PER_REQUEST = 2;

    /** 표를 한 번 만들 때 경유 정류장을 새로 받을 노선 수 상한. 남은 것은 다음 번에 채워진다. */
    private static final int LOW_FLOOR_ROUTES_PER_BUILD = 25;

    /** 관측 씨앗을 뿌릴 때 훑을 정류장 수. 지역 전체에 고르게 흩어 고른다. */
    private static final int LOW_FLOOR_SEED_STOPS = 12;

    /**
     * 경유 정류장을 못 받은 노선과 그 실패 횟수.
     *
     * <p>표를 다 채울 때까지 이어 받게 만들었기 때문에(아래 {@code buildLowFloorStops} 끝부분)
     * 포기할 줄 모르면 <b>영영 안 오는 노선 하나 때문에 백그라운드가 계속 돈다.</b>
     * 빈 응답은 몰아쳤을 때도 오므로 한 번 실패로 버리지 않고 세 번까지 본다.
     */
    private final Map<String, Integer> routeStopsFail = new ConcurrentHashMap<>();

    /** 이만큼 실패하면 그 노선은 이번 기동에서 포기한다. */
    private static final int ROUTE_STOPS_MAX_TRY = 3;

    private record CachedRoutes(long atMs, List<BusRouteDTO> routes) { }

    /**
     * 지금 지역의 운행 시간표. 없으면 {@link BusTimetable#empty()} 다.
     *
     * <p>기동할 때 읽는다 — 클래스패스 파일이라 TAGO 와 달리 바깥에 기대지 않는다.
     * 79개 노선짜리 표라 메모리도 무시할 수준이다.
     */
    private BusTimetable timetable = BusTimetable.empty();

    @PostConstruct
    private void init() {
        /*
          시간표부터 읽는다. 버스 제공자와 따로 가는 값이라 — 제공자를 못 만들어도
          (서울처럼) 시간표는 읽혀 있어야 나중에 제공자만 붙이면 바로 돈다.
          없는 지역은 조용히 비어 있는 표로 돈다. 그건 오류가 아니다.
        */
        String csv = timetableSources == null ? null : timetableSources.get(regionId);
        timetable = BusTimetable.load(csv);
        if (timetable.isEmpty()) {
            log.info("시간표 없음 · region='{}' — 실시간 도착정보만으로 안내한다", regionId);
        }

        String source = busSources == null ? null : busSources.get(regionId);

        if (source == null || source.isBlank()) {
            unavailableReason = "이 지역(" + regionId + ")의 버스 정보 출처가 정해져 있지 않습니다."
                    + " application.properties 의 wheelway.bus-sources 를 확인하세요.";
            log.warn(unavailableReason);
            return;
        }

        // '<제공자>:<도시코드>'. 서울은 도시코드가 없어 뒤가 빈다.
        int colon = source.indexOf(':');
        String provider = (colon < 0 ? source : source.substring(0, colon)).trim();
        String cityCode = (colon < 0 ? "" : source.substring(colon + 1)).trim();

        if (serviceKey == null || serviceKey.isBlank()) {
            unavailableReason = "공공데이터포털 서비스키가 없습니다."
                    + " credentials/api_keys.properties 를 확인하세요.";
            log.warn(unavailableReason);
            return;
        }

        switch (provider) {
            case "tago" -> {
                if (cityCode.isBlank()) {
                    unavailableReason = "TAGO 는 도시코드가 있어야 합니다 (예: tago:33320).";
                    log.warn(unavailableReason);
                    return;
                }
                client = new TagoBusClient(serviceKey, cityCode);
                log.info("버스 제공자 = TAGO · region='{}' · cityCode={}", regionId, cityCode);
            }
            /*
              서울(TOPIS)은 구현체를 만들지 않았다. 못 만든 게 아니라 부를 수가 없다 —
              포털은 4종 전부 [승인] 인데 ws.bus.go.kr 이 키를 모른다(headerCd=7 / 인증모듈 에러코드 30).
              존재하지 않는 가짜 키와 응답이 한 글자도 다르지 않아서, '권한 없음'이 아니라
              서울시 서버로 키가 넘어가지 않은 상태다.

              빈 목록을 돌려주지 않는 이유: 화면이 '이 근처에 정류장이 없다'고 잘못 말한다.
              열리면 여기에 TopisBusClient 한 줄을 더하는 것으로 끝난다.
            */
            case "topis" -> {
                unavailableReason = "서울 버스 정보는 아직 열리지 않았습니다."
                        + " 활용신청은 승인됐는데 ws.bus.go.kr 이 서비스키를 인식하지 못합니다"
                        + " (인증모듈 에러코드 30).";
                log.warn("버스 제공자 = TOPIS · region='{}' — {}", regionId, unavailableReason);
            }
            default -> {
                unavailableReason = "모르는 버스 제공자입니다: '" + provider + "'";
                log.warn(unavailableReason);
            }
        }

        /*
          ★ 쌓아 둔 저상 관측을 이어받는다. 제공자를 만든 뒤에 부르는 이유는,
          읽자마자 정류장 표 만들기가 시작될 수 있어서다(lowFloorGen).
          표가 없으면 조용히 예전 방식(메모리에만)으로 돈다.
        */
        loadSeen();
    }

    @Override
    public String providerName() {
        return client == null ? "none" : client.providerName();
    }

    @Override
    public List<BusStopDTO> nearbyStops(double lat, double lng) {
        IBusClient c = require();

        // 소수점 4자리 ≈ 11m. 이보다 잘게 나누면 지도를 조금 미는 것마다 캐시가 빗나간다.
        String key = String.format("%.4f,%.4f", lat, lng);

        Cached hit = stopCache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.atMs() < stopCacheMin * 60_000L) {
            return hit.stops();
        }

        List<BusStopDTO> stops = keepThisCity(c.nearbyStops(lat, lng, nearbyLimit));

        /*
          ★ 빈 결과는 캐시하지 않는다.

          TAGO 가 같은 좌표에 대해 가끔 빈 목록을 돌려준다 — 오류가 아니라 200/resultCode=00
          인데 items 만 비어 있다(경유노선도 호출마다 23→22→18→0 으로 흔들리는 것을 봤다).
          그걸 캐시하면 그 자리가 30분 동안 '정류장 없는 동네'가 된다. 실제로 그렇게 됐다 —
          바로 옆 좌표는 8곳이 나오는데 이 좌표만 0곳이었다.

          다시 부르는 값은 호출 한 번이고, 사용자가 [찾기] 를 다시 눌러 답이 바뀌는 편이
          '여긴 정류장이 없다'는 거짓말보다 낫다.
        */
        if (!stops.isEmpty()) {
            stopCache.put(key, new Cached(now, stops));
        }

        // 지도를 여기저기 옮기면 키가 계속 늘어난다. 정류장 목록은 가벼우니 상한만 둔다.
        if (stopCache.size() > 500) {
            stopCache.clear();
        }
        return stops;
    }

    /**
     * 근접정류소 결과에서 <b>이 도시 것이 아닌 정류장</b>을 걸러낸다.
     *
     * <p><b>★ TAGO 근접정류소는 cityCode 를 줘도 다른 지역 정류장을 섞어 준다.</b>
     * 청주시청 앞에서 실측한 결과다.
     *
     * <pre>
     *   SJB270051543  시청 신청사예정지   89m   → 도착정보 0건 · 경유노선 0건
     *   SJB270000008  시청 신청사예정지   98m   → 0건
     *   CJB270000008  시청 신청사예정지   98m   → 도착 14건 · 노선 23개   ← 진짜
     *   BEB270000012  시청               98m   → 0건
     * </pre>
     *
     * 목록이 가까운 순이라 <b>맨 위 세 곳이 전부 죽은 정류장</b>이었다. 사용자가 그걸 누르면
     * '이 정류장에는 오는 버스가 없다'는 화면을 보는데, 실제로는 9m 옆에 14대가 오고 있다.
     *
     * <p>가리는 기준은 <b>지역 전체 목록</b>({@code getSttnNoList})이다. 그게 이 도시의
     * 정식 명부이고, 도착정보도 그 안의 것만 답한다. 청주 표본 300곳이 전부 {@code CJB} 였고
     * 위 SJB·BEB 는 하나도 들어 있지 않았다.
     *
     * <p>명부를 아직 못 받았으면 <b>거르지 않고 그대로 돌려준다</b> —
     * 명부가 없다고 정류장을 통째로 감추면 '이 근처에 정류장이 없다'는 거짓이 된다.
     */
    private List<BusStopDTO> keepThisCity(List<BusStopDTO> stops) {
        if (stops.isEmpty()) {
            return stops;
        }

        List<BusStopDTO> all = allStopsCached();
        if (all == null || all.isEmpty()) {
            return stops;
        }

        java.util.Set<String> known = new java.util.HashSet<>();
        for (BusStopDTO s : all) {
            known.add(s.stopId());
        }

        List<BusStopDTO> kept = stops.stream()
                .filter(s -> known.contains(s.stopId()))
                .toList();

        if (kept.size() < stops.size()) {
            log.info("근접정류소에서 다른 지역 정류장 {}곳을 걸렀습니다 ({}곳 → {}곳)",
                    stops.size() - kept.size(), stops.size(), kept.size());
        }
        return kept;
    }

    @Override
    public List<BusStopDTO> stopsInBounds(double minLat, double minLng,
                                          double maxLat, double maxLng, int limit) {
        List<BusStopDTO> cached = allStopsCached();
        if (cached == null) {
            return List.of();
        }

        List<BusStopDTO> inside = cached.stream()
                .filter(s -> s.latitude() >= minLat && s.latitude() <= maxLat
                        && s.longitude() >= minLng && s.longitude() <= maxLng)
                .toList();

        if (inside.size() <= limit) {
            return inside;
        }

        /*
          넓게 보면 수백 개가 된다. 다 그리면 지도가 점으로 덮이고 브라우저도 느려진다.
          자를 때는 <b>화면 한가운데에 가까운 것부터</b> 남긴다 — 사용자가 보고 있는 자리가 거기다.
        */
        double cLat = (minLat + maxLat) / 2;
        double cLng = (minLng + maxLng) / 2;
        return inside.stream()
                .sorted(java.util.Comparator.comparingDouble(
                        s -> Math.pow(s.latitude() - cLat, 2) + Math.pow(s.longitude() - cLng, 2)))
                .limit(limit)
                .toList();
    }

    /**
     * 지역 전체 정류장 캐시. 없으면 한 번 받아 온다. 못 받으면 {@code null}.
     *
     * <p>지도 범위 조회와 이름 검색이 <b>같은 목록</b>을 본다. 따로 받으면
     * 838곳짜리 응답이 두 번 오가고, 한쪽만 갱신돼 어긋나는 순간도 생긴다.
     */
    private List<BusStopDTO> allStopsCached() {
        IBusClient c = require();

        long now = System.currentTimeMillis();
        List<BusStopDTO> cached = allStops;

        if (cached == null || now - allStopsAtMs >= ROUTE_HOURS_TTL_MS) {
            List<BusStopDTO> loaded = c.allStops();
            // 빈 결과는 담지 않는다 — 담으면 하루 동안 '정류장 없는 지역'이 된다.
            if (!loaded.isEmpty()) {
                allStops = loaded;
                allStopsAtMs = now;
                cached = loaded;
            }
        }
        return cached;
    }

    @Override
    public List<BusStopDTO> findStops(String q, double[] near, int limit) {
        if (q == null || q.isBlank()) {
            return List.of();
        }

        List<BusStopDTO> cached = allStopsCached();
        if (cached == null) {
            return List.of();
        }

        String needle = q.trim().toLowerCase();

        /*
          이름에 들어 있으면 걸리게 한다(앞부분 일치로 좁히지 않는다).
          '보은여고' 를 찾는 사람이 '여고' 만 칠 수도 있고, 정류장 이름에는
          '앞'·'입구' 같은 꼬리가 붙어 있어(보은군청입구) 앞뒤 어디서든 맞아야 한다.
        */
        List<BusStopDTO> hit = cached.stream()
                .filter(s -> s.stopName() != null
                        && s.stopName().toLowerCase().contains(needle))
                .toList();

        /*
          같은 이름이 방향별로 여러 개 있는 것이 정상이다(보은군청입구 상·하행).
          그걸 합치지 않는다 — 방향을 고르는 것이 곧 '어느 쪽으로 가는 버스인가' 라서,
          하나로 줄이면 사용자가 반대편에서 오지 않는 버스를 기다리게 된다(5-7).

          가까운 순으로 세운다. 이름순으로 두면 같은 이름 둘 중 어느 것이 내 쪽인지
          알 길이 없다. distanceM 은 여기서 다시 계산해 채운다.
        */
        java.util.Comparator<BusStopDTO> order = near == null
                ? java.util.Comparator.comparing(BusStopDTO::stopName)
                : java.util.Comparator.comparingDouble(s -> dist2(s, near));

        return hit.stream()
                .sorted(order)
                .limit(Math.max(1, limit))
                .map(s -> near == null ? s
                        : new BusStopDTO(s.stopId(), s.stopName(), s.latitude(), s.longitude(),
                                         (int) Math.round(Math.sqrt(dist2(s, near)) * 111_000)))
                .toList();
    }

    /** 정렬용 거리 제곱(도 단위). 순서만 필요할 때는 제곱근을 안 씌워도 된다. */
    private static double dist2(BusStopDTO s, double[] near) {
        double dLat = s.latitude() - near[0];
        // 경도 1도는 위도에 따라 짧아진다. 안 보정하면 동서로 늘어진 순서가 나온다.
        double dLng = (s.longitude() - near[1]) * Math.cos(Math.toRadians(near[0]));
        return dLat * dLat + dLng * dLng;
    }

    /**
     * 저상 노선이 서는 정류장 전부. {@code stopId → 그 정류장에 서는 저상 노선번호들}.
     *
     * <p>정류장마다 '여기 저상이 서나' 를 묻지 않는다 — 후보 수만큼 호출이 나가고
     * 몰아치면 빈 응답이 온다. 대신 <b>저상 노선 쪽에서</b> 경유 정류장을 받는다.
     * 보은은 저상이 5개 노선뿐이라 호출 5번이면 저상이 서는 정류장 전부를 알 수 있다.
     */
    /**
     * <b>관측으로 쌓는 저상 노선 표.</b> {@code 노선번호 → 저상차를 몇 번 봤나 / 전부 몇 번 봤나}.
     *
     * <p><b>왜 필요한가</b>: 저상 노선을 가리는 근거가 지금까지 보은 시간표의 {@code 저상운행}
     * 칸이었는데, 그건 보은 전용 파일이다. 청주로 옮기니 근거가 사라져
     * '걸어가서 탈 수 있는 정류장'이 통째로 멈췄다.
     *
     * <p>TAGO 노선 목록에는 저상 정보가 <b>없다</b>(실제 응답을 확인했다 —
     * {@code routeno · routetp · startvehicletime · endvehicletime} 이 전부다).
     * 대신 <b>도착정보의 {@code vehicletp} 가 차량 단위로 온다.</b> 그걸 지나가면서 적어 두면
     * 표가 저절로 쌓인다 — <b>추가 호출이 0</b>이다. 어차피 45초마다 부르고 있던 응답이다.
     *
     * <p>시간표의 {@code Y/N} 보다 정확하다. 그건 노선 단위 신고값이고 이건 실제로 온 차다.
     *
     * <p><b>한 번이라도 저상차를 봤으면 저상 노선으로 본다.</b> 비율을 따지지 않는 이유는
     * 우리가 답하려는 것이 '이 노선에 저상차가 다니기는 하는가' 이기 때문이다.
     * 몇 대 중 몇 대인지는 도착 목록이 그때그때 보여준다.
     *
     * <p><b>메모리에만 있다.</b> 재기동하면 비고 다시 쌓인다 — 청주는 배차가 촘촘해
     * 정류장 몇 곳만 훑어도 금방 찬다(아래 씨앗 뿌리기). 영속화 DDL 은 인계서에 있다.
     */
    private final Map<String, int[]> lowFloorSeen = new ConcurrentHashMap<>();

    /**
     * 저상 노선이 새로 발견될 때마다 오른다. 정류장 표의 캐시 열쇠다 —
     * 새 노선을 봤는데 표가 하루 동안 옛것이면 그 노선이 서는 정류장이 안 뜬다.
     */
    private final java.util.concurrent.atomic.AtomicInteger lowFloorGen =
            new java.util.concurrent.atomic.AtomicInteger();

    private volatile Map<String, List<String>> lowFloorStops = null;
    private volatile int lowFloorStopsGen = -1;
    private volatile boolean lowFloorBuilding = false;

    @Override
    public List<BoardingStop> boardingStops(double lat, double lng, int limit) {
        Map<String, List<String>> served = lowFloorStopTable();
        if (served == null || served.isEmpty()) {
            return List.of();
        }

        List<BusStopDTO> all = allStopsCached();
        if (all == null) {
            return List.of();
        }

        double[] near = { lat, lng };

        return all.stream()
                .filter(s -> served.containsKey(s.stopId()))
                .sorted(java.util.Comparator.comparingDouble(s -> dist2(s, near)))
                .limit(Math.max(1, limit))
                .map(s -> new BoardingStop(
                        // 거리를 여기서 채운다. 전체 목록의 distanceM 은 기준이 없어 0 이다.
                        new BusStopDTO(s.stopId(), s.stopName(), s.latitude(), s.longitude(),
                                (int) Math.round(Math.sqrt(dist2(s, near)) * 111_000)),
                        served.get(s.stopId())))
                .toList();
    }

    /**
     * 도착정보에서 저상 여부를 적어 둔다. {@code lowFloor} 가 {@code null} 인 것은 세지 않는다 —
     * '모른다' 를 '일반' 으로 세면 저상 노선을 영영 못 찾는다.
     */
    private void observeLowFloor(List<BusArrivalDTO> arrivals) {
        for (BusArrivalDTO a : arrivals) {
            if (a.routeNo() == null || a.lowFloor() == null) {
                continue;
            }
            int[] seen = lowFloorSeen.computeIfAbsent(a.routeNo(), k -> new int[2]);
            boolean isNew;
            synchronized (seen) {
                isNew = a.lowFloor() && seen[0] == 0;
                if (a.lowFloor()) {
                    seen[0]++;
                }
                seen[1]++;
            }
            if (isNew) {
                lowFloorGen.incrementAndGet();      // 표를 다시 만들어야 한다
                log.info("저상 노선 발견 · {}번 (지금까지 {}개)", a.routeNo(), lowFloorRouteNos().size());

                // ★ 이 사실만은 바로 적는다. 재기동해도 이 노선을 다시 찾아 헤매지 않게.
                saveSeen(a.routeNo());
            }
        }
    }

    /** 지금까지 저상차를 본 적 있는 노선번호. */
    private java.util.Set<String> lowFloorRouteNos() {
        java.util.Set<String> out = new java.util.HashSet<>();
        lowFloorSeen.forEach((no, seen) -> {
            if (seen[0] > 0) {
                out.add(no);
            }
        });
        return out;
    }

    /**
     * 이 노선에 저상차가 다니는가. <b>근거가 둘이고 순서가 있다.</b>
     *
     * <pre>
     *   ① 시간표의 저상운행 칸   있으면 그것 (보은)
     *   ② 도착정보 관측         시간표가 없는 지역 (청주)
     * </pre>
     *
     * <p>시간표를 먼저 보는 이유: 보은 저상 노선은 <b>실시간에 아예 안 잡혀서</b>
     * 관측으로는 영원히 못 찾는다. 거꾸로 청주는 시간표가 없고 관측이 잘 된다.
     * 둘 다 없는 지역이면 저상 안내를 못 하고, 그건 사실대로 빈 목록이 된다.
     */
    private boolean isLowFloorRoute(String routeNo) {
        BusTimetable.Route t = timetable.route(routeNo);
        if (t != null) {
            return t.lowFloorRoute();
        }
        int[] seen = lowFloorSeen.get(routeNo);
        return seen != null && seen[0] > 0;
    }

    /**
     * 저상 노선이 서는 정류장 표. <b>없으면 백그라운드에서 만들고 지금 있는 것을 돌려준다.</b>
     *
     * <p>요청 안에서 만들지 않는 이유: 노선마다 경유 정류장을 받아야 해서
     * 노선 수 × 0.6초가 걸린다. 청주처럼 저상이 수십 개면 요청 하나가 30초를 잡아먹는다.
     * 화면은 표가 빌 동안 제안 줄을 안 띄우기만 하면 되고, 다음 조회에서 채워진다.
     */
    private Map<String, List<String>> lowFloorStopTable() {
        int gen = lowFloorGen.get();
        Map<String, List<String>> cached = lowFloorStops;

        if (cached != null && lowFloorStopsGen == gen) {
            return cached;
        }
        startLowFloorBuild(gen);
        return cached;                      // 아직 없으면 null. 부르는 쪽이 빈 목록으로 답한다
    }

    private synchronized void startLowFloorBuild(int gen) {
        if (lowFloorBuilding) {
            return;
        }
        lowFloorBuilding = true;

        Thread t = new Thread(() -> {
            try {
                buildLowFloorStops(gen);
            } catch (RuntimeException e) {
                log.warn("저상 정류장 표를 만들지 못했습니다: {}", e.getMessage());
            } finally {
                lowFloorBuilding = false;
            }
        }, "lowfloor-table");

        t.setDaemon(true);      // 이것 때문에 서버가 안 내려가면 안 된다
        t.start();
    }

    /** 저상 노선의 경유 정류장을 모아 {@code stopId → 저상 노선번호들} 을 만든다. */
    private void buildLowFloorStops(int gen) {
        IBusClient c = client;
        if (c == null) {
            return;
        }

        Map<String, BusRouteDTO> routes = routeHoursTable(c);
        if (routes == null) {
            return;
        }

        // 관측이 아직 얕으면 씨앗을 뿌린다. 시간표가 있는 지역(보은)은 필요 없다.
        if (timetable.isEmpty() && lowFloorRouteNos().isEmpty()) {
            seedLowFloorObservations(c);
        }

        Map<String, List<String>> built = new java.util.HashMap<>();
        int fetched = 0;
        int remaining = 0;      // 아직 경유 정류장을 못 받은 저상 노선 수

        for (BusRouteDTO r : routes.values()) {
            if (!isLowFloorRoute(r.routeNo())) {
                continue;
            }

            List<RouteStopDTO> stops = routeStopsCache.get(r.routeId());
            if (stops == null) {
                // 세 번이나 빈손으로 온 노선은 이번 기동에서 포기한다. 안 그러면 계속 다시 부른다.
                if (routeStopsFail.getOrDefault(r.routeId(), 0) >= ROUTE_STOPS_MAX_TRY) {
                    continue;
                }
                // 한 번에 다 받지 않는다. 남은 것은 다음 번에 채워진다.
                if (fetched >= LOW_FLOOR_ROUTES_PER_BUILD) {
                    remaining++;
                    continue;
                }
                fetched++;
                try {
                    stops = c.routeStops(r.routeId());
                } catch (RuntimeException e) {
                    log.warn("경유 정류장 실패 ({}번): {}", r.routeNo(), e.getMessage());
                    routeStopsFail.merge(r.routeId(), 1, Integer::sum);
                    remaining++;
                    continue;
                }
                if (stops.isEmpty()) {
                    routeStopsFail.merge(r.routeId(), 1, Integer::sum);
                    remaining++;
                    continue;
                }
                routeStopsCache.put(r.routeId(), stops);
                sleepQuietly(600);     // 몰아치면 오류가 아니라 빈 응답이 온다
            }

            for (RouteStopDTO st : stops) {
                List<String> nos = built.computeIfAbsent(st.stopId(), k -> new ArrayList<>());
                if (!nos.contains(r.routeNo())) {
                    nos.add(r.routeNo());
                }
            }
        }

        if (built.isEmpty()) {
            return;
        }
        lowFloorStops = built;

        /*
          ★ 다 못 받았으면 <b>세대를 확정하지 않는다.</b> 그러면 다음 조회가 이어서 25개를 더 받는다.

          전에는 여기서 무조건 확정했다. 그래도 돌아간 것은 사용자가 정류장을 눌러 볼 때마다
          저상 노선이 새로 발견돼(gen 이 올라) 표를 다시 만들었기 때문이다.
          관측을 DB 에 살려 두기 시작하면 그 발견이 안 일어난다 — 기동하자마자 다 알고 있으니까.
          그러면 25개까지만 채워진 표가 그대로 굳어서, 26번째 노선이 지나는 정류장은
          '저상이 안 서는 곳' 으로 남는다. 조용히 틀리는 종류의 문제라 여기서 막는다.
        */
        if (remaining > 0) {
            log.info("저상 정류장 표 (이어받는 중) · 정류장 {}곳 · 이번에 받은 노선 {} · 남은 노선 {}",
                    built.size(), fetched, remaining);
        } else {
            lowFloorStopsGen = gen;
            log.info("저상 정류장 표 · 노선 {}개 · 정류장 {}곳 (이번에 새로 받은 노선 {})",
                    lowFloorRouteNos().size(), built.size(), fetched);
        }
    }

    /**
     * 관측이 하나도 없을 때 <b>정류장 몇 곳을 훑어 씨앗을 만든다.</b>
     *
     * <p>가만두면 사용자가 정류장을 눌러 볼 때만 쌓여서, 처음 쓰는 사람은 며칠 동안
     * 이 기능을 못 본다. 청주는 배차가 촘촘해 정류장 한 곳에 도착이 14건씩 오므로
     * 몇 곳만 훑어도 금방 찬다(실측: 시청 한 곳에서 저상 8대).
     *
     * <p>지역 전체에 고르게 흩어진 곳을 고른다 — 한 동네만 보면 그 동네 노선만 찬다.
     */
    private void seedLowFloorObservations(IBusClient c) {
        List<BusStopDTO> all = allStopsCached();
        if (all == null || all.isEmpty()) {
            return;
        }

        int step = Math.max(1, all.size() / LOW_FLOOR_SEED_STOPS);
        int done = 0;

        for (int i = 0; i < all.size() && done < LOW_FLOOR_SEED_STOPS; i += step) {
            try {
                observeLowFloor(c.arrivals(all.get(i).stopId()));
            } catch (RuntimeException e) {
                /* 한 곳 실패는 넘어간다. 씨앗은 여러 곳에서 모으는 것이라 하나가 빠져도 된다 */
            }
            done++;
            sleepQuietly(1200);
        }
        log.info("저상 관측 씨앗 · 정류장 {}곳 훑음 → 저상 노선 {}개",
                done, lowFloorRouteNos().size());
    }

    /* ── 복합 경로의 재료 ───────────────────────────────────────
       여기부터는 '어디서 타서 어디서 내리나' 를 답하는 자리다. 위쪽(정류장·도착 안내)은
       전부 정류장 한 곳을 보는 기능이었고, 이건 두 곳을 잇는다.

       ★ 새 API 를 부르지 않는다. 이미 받아 캐시해 둔 것 — 경유 정류장(routeStopsCache)과
       도착정보 — 만으로 만든다. 노선을 새로 받으러 가면 조합 수만큼 호출이 나가고,
       몰아치면 오류가 아니라 빈 응답이 온다(여러 번 겪었다). */

    /**
     * 노선번호 → {@code {누적 거리(m), 누적 시간(초)}}. <b>실제로 달린 것을 잰 값이다.</b>
     *
     * <p><b>추가 호출이 0 이다.</b> 도착정보에는 '몇 정거장 앞에 있고 몇 초 뒤 도착'이 같이 온다.
     * 그 버스가 지금 어느 정류장 근처인지는 경유 정류장 목록에서 순번을 세면 나오고,
     * 거기서 이 정류장까지의 거리는 좌표로 잰다. 그 둘을 나누면 <b>그 노선의 진짜 표정속도</b>다.
     * 저상 여부를 관측으로 쌓는 것과 같은 방식이고, 같은 응답을 한 번 더 읽을 뿐이다.
     *
     * <p><b>왜 필요한가</b>: 청주에는 시간표가 없다. 시간표가 있는 보은은 '편도 몇 분'을
     * 표에서 읽을 수 있지만, 청주에서 그걸 못 구하면 '버스로 몇 분'을 아예 말할 수 없다.
     * 지역 평균 속도를 박아 쓰는 것보다 그 노선을 실제로 잰 값이 낫다 —
     * 같은 도시에서도 간선과 지선은 속도가 다르다.
     *
     * <p>거리와 시간을 <b>따로</b> 쌓고 나중에 나눈다. 속도를 매번 평균 내면
     * 3초짜리 관측과 300초짜리 관측이 같은 무게가 된다.
     */
    private final Map<String, double[]> routeSpeed = new ConcurrentHashMap<>();

    /** 이만큼은 쌓여야 속도를 말한다(초). 한 대만 보고 정하면 그 차의 신호운이 그대로 들어간다. */
    private static final double SPEED_MIN_SEC = 180;

    /**
     * 노선을 따라가는 거리가 직선의 몇 배까지면 '그 방향으로 가는 것'으로 볼지.
     *
     * <p>버스 노선은 원래 굽이치므로 1.5배쯤은 예사다. 3배를 넘는 것은 굽은 것이 아니라
     * <b>반대로 가는 것</b>이다 — 회차점을 돌아 되돌아오는 구간이 그렇게 잡힌다.
     */
    private static final double MAX_DETOUR_RATIO = 3.0;

    /**
     * 도착정보에서 그 노선의 주행 속도를 적어 둔다.
     *
     * <p><b>경유 정류장을 아직 못 받은 노선은 그냥 지나간다.</b> 여기서 받아오면
     * 도착 안내 한 번에 노선 수만큼 호출이 나간다 — 이 함수는 곁다리라 그럴 자격이 없다.
     */
    private void observeSpeed(String stopId, List<BusArrivalDTO> arrivals) {
        for (BusArrivalDTO a : arrivals) {
            if (a.routeId() == null || a.routeNo() == null
                    || a.arriveSec() == null || a.prevStationCount() == null) {
                continue;
            }
            /*
              너무 가까이 온 차는 안 센다. 코앞의 60초에는 정차와 승하차가 큰 몫을 차지해서
              그걸로 잰 속도는 노선의 속도가 아니라 '그 정류장에 들어오는 속도'가 된다.
            */
            if (a.arriveSec() < 60 || a.prevStationCount() < 2) {
                continue;
            }

            List<RouteStopDTO> stops = routeStopsCache.get(a.routeId());
            if (stops == null) {
                continue;
            }
            int at = indexOfStop(stops, stopId);
            int from = at - a.prevStationCount();
            if (at < 0 || from < 0) {
                continue;
            }

            double m = pathMeters(stops, from, at);
            if (m <= 0) {
                continue;
            }
            double mps = m / a.arriveSec();

            /*
              말이 안 되는 값은 버린다. 5.4~90km/h 밖이면 순번이 어긋났거나
              (같은 이름의 다른 정류장) 차가 회차 중인 경우다.
            */
            if (mps < 1.5 || mps > 25) {
                continue;
            }

            double[] acc = routeSpeed.computeIfAbsent(a.routeNo(), k -> new double[2]);
            synchronized (acc) {
                acc[0] += m;
                acc[1] += a.arriveSec();
            }
        }
    }

    /* ── 관측을 살려 둔다 (BUS_LOW_FLOOR_SEEN) ──────────────────
       ★ 이 표가 없으면 재기동 직후 몇십 초 동안 화면이 사실이 아닌 말을 한다 —
       '저상버스가 서는 정류장이 없습니다'. 없는 것이 아니라 아직 안 쌓인 것이다.
       관측을 살려 두면 뜨자마자 아는 상태로 시작한다.

       ★ 표가 없어도 앱은 그대로 돈다. 예전처럼 메모리에만 쌓을 뿐이다 —
       DDL 을 아직 안 돌린 환경에서 기동이 실패하면 안 된다. */

    /** 이 표를 쓸 수 있는가. 한 번 실패하면 끄고 다시 건드리지 않는다(매 요청마다 예외를 던지지 않게). */
    private volatile boolean seenTable = true;

    /** 마지막으로 DB 에 적은 때. 관측은 초 단위로 늘어나므로 매번 적지 않는다. */
    private volatile long seenFlushAtMs = 0;

    /** 얼마나 자주 적을지. 짧게 잡을 이유가 없다 — 잃어도 다음 관측에서 금방 다시 찬다. */
    private static final long SEEN_FLUSH_MS = 5 * 60_000L;

    /**
     * 쌓아 둔 관측을 읽어 온다. 기동할 때 한 번.
     *
     * <p>읽은 뒤 {@code lowFloorGen} 을 올리는 이유: 저상 노선 목록이 방금 바뀐 것과 같으므로
     * 정류장 표를 만들어야 한다. 안 올리면 표는 비어 있는데 노선은 아는 어정쩡한 상태가 된다.
     */
    private void loadSeen() {
        if (seenMapper == null) {
            return;
        }
        try {
            List<kopo.poly.dto.LowFloorSeenDTO> rows = seenMapper.getSeen(regionId);
            for (kopo.poly.dto.LowFloorSeenDTO r : rows) {
                lowFloorSeen.put(r.routeNo(), new int[] { r.lowCnt(), r.totalCnt() });
            }
            if (!rows.isEmpty()) {
                lowFloorGen.incrementAndGet();
                log.info("저상 관측을 이어받았습니다 · {}개 노선 (저상 {}개)",
                        rows.size(), lowFloorRouteNos().size());
            }
        } catch (RuntimeException e) {
            /*
              표가 아직 없는 것이 가장 흔한 원인이다. 그건 오류가 아니라 '아직 안 만든 것'이라
              한 번만 알리고 넘어간다 — DDL 은 사람이 직접 돌린다.
            */
            seenTable = false;
            log.info("저상 관측 표를 쓸 수 없어 메모리에만 쌓습니다 (BUS_LOW_FLOOR_SEEN): {}",
                    e.getMessage());
        }
    }

    /** 노선 하나를 바로 적는다. <b>저상차를 처음 본 순간</b>에만 부른다 — 그게 잃으면 아까운 값이다. */
    private void saveSeen(String routeNo) {
        if (!seenTable || seenMapper == null) {
            return;
        }
        int[] acc = lowFloorSeen.get(routeNo);
        if (acc == null) {
            return;
        }
        try {
            synchronized (acc) {
                seenMapper.upsertSeen(new kopo.poly.dto.LowFloorSeenDTO(
                        regionId, routeNo, acc[0], acc[1]));
            }
        } catch (RuntimeException e) {
            seenTable = false;
            log.warn("저상 관측을 적지 못했습니다. 이번 기동에서는 메모리에만 쌓습니다: {}", e.getMessage());
        }
    }

    /**
     * 쌓인 관측을 통째로 적는다. 요청 안에서 하지 않는다 — 노선 수만큼 UPDATE 가 나간다.
     *
     * <p>자주 하지 않는 이유: 여기서 잃는 것은 <b>횟수</b>뿐이고, 정작 중요한
     * '저상 노선인가'는 처음 본 순간에 이미 적혔다({@link #saveSeen}).
     */
    private void flushSeenIfDue() {
        if (!seenTable || seenMapper == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - seenFlushAtMs < SEEN_FLUSH_MS) {
            return;
        }
        seenFlushAtMs = now;

        Thread t = new Thread(() -> {
            try {
                lowFloorSeen.forEach((no, acc) -> {
                    synchronized (acc) {
                        seenMapper.upsertSeen(new kopo.poly.dto.LowFloorSeenDTO(
                                regionId, no, acc[0], acc[1]));
                    }
                });
            } catch (RuntimeException e) {
                seenTable = false;
                log.warn("저상 관측을 적지 못했습니다: {}", e.getMessage());
            }
        }, "lowfloor-flush");

        t.setDaemon(true);      // 이것 때문에 서버가 안 내려가면 안 된다
        t.start();
    }

    /** 관측으로 얻은 그 노선의 주행 속도(m/s). 아직 얕으면 {@code null} — 지어내지 않는다. */
    private Double observedSpeedMps(String routeNo) {
        double[] acc = routeSpeed.get(routeNo);
        if (acc == null) {
            return null;
        }
        synchronized (acc) {
            return acc[1] < SPEED_MIN_SEC ? null : acc[0] / acc[1];
        }
    }

    @Override
    public List<kopo.poly.dto.BusLinkDTO> directLinks(java.util.Collection<String> fromStopIds,
                                                      java.util.Collection<String> toStopIds,
                                                      int limit) {

        if (fromStopIds == null || toStopIds == null || fromStopIds.isEmpty() || toStopIds.isEmpty()) {
            return List.of();
        }

        /*
          저상 정류장 표를 먼저 건드린다. 없으면 여기서 백그라운드 만들기가 시작되고
          이번에는 빈 목록이 나간다 — 다음 조회에서 채워진다. 요청 안에서 만들지 않는 이유는
          boardingStops 와 같다(노선 수 × 0.6초).
        */
        Map<String, List<String>> served = lowFloorStopTable();
        if (served == null || served.isEmpty()) {
            return List.of();
        }

        IBusClient c = client;
        if (c == null) {
            return List.of();
        }
        Map<String, BusRouteDTO> routes = routeHoursTable(c);
        if (routes == null) {
            return List.of();
        }

        java.util.Set<String> from = new java.util.HashSet<>(fromStopIds);
        java.util.Set<String> to = new java.util.HashSet<>(toStopIds);
        List<kopo.poly.dto.BusLinkDTO> out = new ArrayList<>();

        for (BusRouteDTO r : routes.values()) {
            if (!isLowFloorRoute(r.routeNo())) {
                continue;
            }
            List<RouteStopDTO> stops = routeStopsCache.get(r.routeId());
            if (stops == null || stops.size() < 2) {
                continue;       // 아직 경유 정류장을 못 받은 노선. 여기서 받으러 가지 않는다
            }

            /*
              순번을 따라 훑으며 (승차 후보, 그 뒤에 오는 하차 후보) 쌍을 전부 만든다.
              ★ 첫 하차 후보에서 멈추면 안 된다 — 노선을 따라 먼저 오는 정류장이
              목적지에 더 가깝다는 보장이 없다. 어느 쌍이 나은지는 도보 거리까지 봐야
              알 수 있고, 그건 부르는 쪽이 판단한다.
            */
            for (int i = 0; i < stops.size(); i++) {
                if (!from.contains(stops.get(i).stopId())) {
                    continue;
                }

                /*
                  ★ 하차 후보마다 <b>가장 먼저 나오는 자리</b>만 잡는다.

                  왕복·순환 노선은 목록에 기점→종점→기점이 통째로 들어 있어서 같은 정류장이
                  두 번 나온다. 뒤엣것을 잡으면 '한 바퀴 다 돌고 내리는' 안이 만들어진다 —
                  실측으로 105 정거장 60km 짜리가 나왔다(4km 떨어진 곳으로 가는 길이었다).
                */
                java.util.Set<String> taken = new java.util.HashSet<>();

                for (int j = i + 1; j < stops.size(); j++) {
                    String id = stops.get(j).stopId();
                    if (!to.contains(id) || !taken.add(id)) {
                        continue;
                    }

                    /*
                      ★ 그래도 남는 것이 있다. 반대 방향에서 탄 경우다 —
                      그 정류장에서는 정말로 한 바퀴를 돌아야 목적지에 닿는다.
                      길을 따라가는 거리가 직선의 세 배를 넘으면 <b>여기서 탈 자리가 아니다.</b>
                      맞은편이나 다른 정류장에서 타는 안이 따로 나오므로 버려도 잃는 것이 없다.
                    */
                    double straight = haversineM(stops.get(i).latitude(), stops.get(i).longitude(),
                            stops.get(j).latitude(), stops.get(j).longitude());
                    double along = pathMeters(stops, i, j);

                    // 300m 를 더해 두는 것은 아주 짧은 구간을 위해서다 —
                    // 두 정류장이 200m 붙어 있으면 정상적인 노선도 배수가 쉽게 3을 넘는다.
                    if (along > straight * MAX_DETOUR_RATIO + 300) {
                        continue;
                    }
                    out.add(makeLink(r, stops, i, j));
                }
            }
        }

        /*
          짧게 타는 것부터 준다. 부르는 쪽이 도보까지 더해 다시 세우므로 여기서의 순서는
          '상한에 걸려 잘릴 때 무엇을 남길까' 만 정한다 — 그때는 짧은 쪽이 살아남는 편이 낫다.
        */
        out.sort(java.util.Comparator.comparingInt(kopo.poly.dto.BusLinkDTO::rideMeters));
        return out.size() > limit ? new ArrayList<>(out.subList(0, Math.max(1, limit))) : out;
    }

    /** 노선 하나의 {@code i → j} 구간을 링크 한 줄로 만든다. */
    private kopo.poly.dto.BusLinkDTO makeLink(BusRouteDTO r, List<RouteStopDTO> stops, int i, int j) {
        double rideM = pathMeters(stops, i, j);

        Integer rideMin;
        String source;

        /*
          시간이 어디서 오는지에 순서가 있다. 저상 판별(isLowFloorRoute)과 같은 생각이다 —
          지역이 가진 것이 다르므로, 있는 것 중 가장 근거 있는 것을 쓴다.

            ① 시간표   편도 소요시간을 거리 비율로 나눈다 (보은)
            ② 관측     그 노선을 실제로 잰 속도            (청주)
            ③ 어림     기본 속도. 근거가 약하다는 것을 화면에 밝힌다
        */
        Integer runMin = timetable.runMin(r.routeNo());
        double totalM = pathMeters(stops, 0, stops.size() - 1);

        if (runMin != null && totalM > 0) {
            rideMin = Math.max(1, (int) Math.round(runMin * (rideM / totalM)));
            source = "timetable";
        } else {
            Double mps = observedSpeedMps(r.routeNo());
            double use = mps != null ? mps : busSpeedKmh / 3.6;
            rideMin = Math.max(1, (int) Math.ceil(rideM / use / 60));
            source = mps != null ? "observed" : "assumed";
        }

        List<double[]> path = new ArrayList<>(j - i + 1);
        for (int k = i; k <= j; k++) {
            path.add(new double[] { stops.get(k).latitude(), stops.get(k).longitude() });
        }

        /*
          정거장 수는 순번 차이가 아니라 목록에서의 자리 차이로 센다.
          nodeord 가 중간에 건너뛰는 노선이 있어서(회차 구간) 순번을 빼면 부풀려진다.
        */
        return new kopo.poly.dto.BusLinkDTO(r.routeId(), r.routeNo(),
                stops.get(i), stops.get(j), j - i,
                (int) Math.round(rideM), rideMin, source, path);
    }

    /** 목록에서 그 정류장이 몇 번째인가. 없으면 -1. */
    private static int indexOfStop(List<RouteStopDTO> stops, String stopId) {
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).stopId().equals(stopId)) {
                return i;
            }
        }
        return -1;
    }

    /** 정류장 {@code i} 부터 {@code j} 까지 좌표를 이어 잰 거리(m). */
    private static double pathMeters(List<RouteStopDTO> stops, int i, int j) {
        double m = 0;
        for (int k = i + 1; k <= j && k < stops.size(); k++) {
            RouteStopDTO p = stops.get(k - 1);
            RouteStopDTO q = stops.get(k);
            m += haversineM(p.latitude(), p.longitude(), q.latitude(), q.longitude());
        }
        return m;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Board boardAt(String stopId) {
        IBusClient c = require();

        long now = System.currentTimeMillis();

        // 같은 순간에 몰린 요청을 한 번으로 묶는다. 12초라 '지금 몇 분 뒤'가 틀어지지 않는다.
        if (boardCacheSec > 0) {
            CachedBoard hit = boardCache.get(stopId);
            if (hit != null && now - hit.atMs() < boardCacheSec * 1000L) {
                return hit.board();
            }
        }

        List<BusArrivalDTO> arrivals = c.arrivals(stopId);

        // ★ 지나가면서 저상 노선을 적어 둔다. 추가 호출 0 — 방금 받은 응답을 읽을 뿐이다.
        observeLowFloor(arrivals);

        // 같은 응답에서 그 노선이 실제로 얼마나 빨리 달리는지도 적어 둔다. 이것도 추가 호출 0 이다.
        observeSpeed(stopId, arrivals);

        // 쌓인 횟수를 이따금 DB 에 적는다(5분에 한 번, 백그라운드).
        flushSeenIfDue();

        /*
          ★ 전에는 '도착정보가 비었을 때만' 경유노선을 불렀다. 호출을 아끼려던 것인데,
          시간표가 붙으면서 그 조건이 틀린 것이 됐다.

          보은 저상 5개 노선은 실시간에 <b>절대</b> 안 잡힌다(전기버스 BIS 미연동).
          그러니 일반차량 한 대가 도착 중이라는 이유로 경유노선을 건너뛰면,
          정작 휠체어로 탈 수 있는 340번의 '다음 12:55' 가 화면에서 통째로 사라진다.
          탈 수 없는 버스가 오고 있다는 사실이, 탈 수 있는 버스의 시각을 가리는 셈이다.

          그래서 늘 부르되 오래 캐시한다(routesCache). 정류장마다 처음 한 번만 호출이다.
        */
        List<BusRouteDTO> routes = decorate(c, stopId, routesAtCached(c, stopId));

        Board board = new Board(arrivals, routes);

        /*
          둘 다 빈 것은 담지 않는다.

          도착 0건은 정상적인 답이지만(배차가 드문 시간대), 경유노선까지 0건이면
          TAGO 가 빈 응답을 준 것이다 — 실제로 호출마다 23 → 22 → 18 → 0 으로 흔들린다.
          그걸 담으면 화면에 '지금 오는 버스가 없습니다' 만 덩그러니 뜨고,
          사용자가 [새로고침] 을 눌러도 12초 동안 같은 화면이라 눌러도 안 되는 것처럼 보인다.
          다시 부르는 값은 호출 한 번이다.
        */
        if (boardCacheSec > 0 && !(board.arrivals().isEmpty() && board.routes().isEmpty())) {
            boardCache.put(stopId, new CachedBoard(now, board));

            // 12초짜리라 오래 남지 않지만, 정류장을 계속 옮겨 다니면 키가 늘기는 한다.
            if (boardCache.size() > 500) {
                boardCache.clear();
            }
        }
        return board;
    }

    /**
     * 이 정류장에 서는 노선. 오래 캐시한다.
     *
     * <p>빈 결과는 담지 않는다 — TAGO 가 오류 없이 빈 목록을 주는 일이 있고,
     * 6시간짜리 캐시에 그게 박히면 그 정류장이 반나절 동안 '서는 노선이 없는 곳'이 된다.
     * 근접정류소에서 똑같은 일을 이미 겪었다.
     */
    private List<BusRouteDTO> routesAtCached(IBusClient c, String stopId) {
        long now = System.currentTimeMillis();

        CachedRoutes hit = routesCache.get(stopId);
        if (hit != null && now - hit.atMs() < routesCacheMin * 60_000L) {
            return hit.routes();
        }

        List<BusRouteDTO> routes;
        try {
            routes = c.routesAt(stopId);
        } catch (RuntimeException e) {
            /*
              경유노선은 곁들이는 정보다. 못 받았다고 도착 안내까지 실패시키면
              본래 기능이 같이 죽는다. 옛 값이 있으면 그거라도 쓴다.
            */
            log.warn("경유노선을 받지 못했습니다 (stopId={}): {}", stopId, e.getMessage());
            return hit == null ? List.of() : hit.routes();
        }

        if (!routes.isEmpty()) {
            routesCache.put(stopId, new CachedRoutes(now, routes));
            if (routesCache.size() > 1000) {
                routesCache.clear();
            }
        }
        return routes;
    }

    /**
     * 경유노선 목록에 첫차·막차, '지금 운행중', 그리고 <b>시간표의 다음 차</b>를 채워 준다.
     *
     * <p>정류장 조회가 주는 노선에는 이 중 아무것도 없다. 두 곳에서 찾아 붙인다.
     *
     * <pre>
     *   첫차·막차   TAGO 지역 노선표   routeId 로 잇는다   운행 <b>시간대</b>
     *   다음 차     시간표 CSV        routeNo 로 잇는다   편별 <b>출발 시각</b>
     * </pre>
     *
     * <p>둘 중 하나가 없어도 나머지는 붙는다. <b>조용히 원래 목록을 돌려주는 것</b>이
     * 기본이다 — 곁들이는 값이 없다고 노선 자체를 안 보여줄 이유는 없다.
     */
    private List<BusRouteDTO> decorate(IBusClient c, String stopId, List<BusRouteDTO> routes) {
        if (routes.isEmpty()) {
            return routes;
        }

        Map<String, BusRouteDTO> table = routeHoursTable(c);
        java.time.LocalDateTime nowDt = java.time.LocalDateTime.now();
        java.time.LocalTime now = nowDt.toLocalTime();
        java.time.DayOfWeek today = nowDt.getDayOfWeek();

        /*
          이번 요청에서 경유 정류장을 새로 받을 수 있는 횟수. 다 쓰면 그 뒤 노선은
          캐시에 있는 것만 쓴다 — 모자란 것은 다음 갱신(45초 뒤)에 채워진다.
          한꺼번에 부르면 빈 응답이 오기 때문이다(routeStopsCache 참고).
        */
        int[] budget = { ROUTE_STOPS_PER_REQUEST };

        return routes.stream().map(r -> {
            String firstTime = r.firstTime();
            String lastTime = r.lastTime();
            Boolean running = r.running();

            BusRouteDTO h = table == null ? null : table.get(r.routeId());
            if (h != null && h.firstTime() != null && h.lastTime() != null) {
                firstTime = h.firstTime();
                lastTime = h.lastTime();
                running = isRunning(firstTime, lastTime, now);
            }

            return new BusRouteDTO(r.routeId(), r.routeNo(), r.routeType(),
                    r.startName(), r.endName(),
                    firstTime, lastTime, running,
                    timetableFor(r.routeNo(), r.routeId(), stopId, today, now, budget));
        }).toList();
    }

    /**
     * 시간표에서 이 노선의 '다음 차'를 만든다. 표에 없는 번호면 {@code null}.
     *
     * <p>다음 두 편까지만 담는다. 하루 두세 편짜리 노선이 흔해서 전부 담아도
     * 몇 개 안 되지만, 화면이 답해야 하는 것은 <b>'지금 나가면 되는가'</b> 하나다.
     * 목록을 길게 늘어놓으면 그 답이 묻힌다.
     */
    private BusTimetableDTO timetableFor(String routeNo, String routeId, String stopId,
                                         java.time.DayOfWeek today,
                                         java.time.LocalTime now,
                                         int[] budget) {

        BusTimetable.Route t = timetable.route(routeNo);
        if (t == null) {
            return null;
        }

        List<BusTimetable.Departure> a = BusTimetable.next(t.fromOrigin(), today, now, 2);
        List<BusTimetable.Departure> b = BusTimetable.next(t.fromDest(), today, now, 2);

        /*
          단서는 '다음 차'에 붙은 것만 올린다. 노선의 모든 단서를 모아 올리면
          지금과 상관없는 말(오전 편의 '법주 경유')이 오후 안내에 따라붙는다.
        */
        String note = a.stream().map(BusTimetable.Departure::note)
                .filter(x -> !x.isBlank()).findFirst()
                .orElseGet(() -> b.stream().map(BusTimetable.Departure::note)
                        .filter(x -> !x.isBlank()).findFirst().orElse(""));

        /*
          ★ 이 정류장까지 버스가 오는 데 몇 분인가.
          저상 노선일 때만 받는다 — 탈 수 있는 버스의 시각만 있으면 되고,
          모든 노선을 받으면 한 정류장에 30회가 연달아 나가 빈 응답이 온다.
        */
        Ride ride = isLowFloorRoute(t.routeNo())
                ? rideToStop(routeId, stopId, t.runMin(), budget)
                : null;

        return new BusTimetableDTO(t.routeNo(), t.originName(), t.destName(),
                a.stream().map(BusTimetable.Departure::hhmm).toList(),
                b.stream().map(BusTimetable.Departure::hhmm).toList(),
                t.runMin(),
                BusTimetable.todayCount(t.fromOrigin(), today)
                        + BusTimetable.todayCount(t.fromDest(), today),
                t.lowFloorRoute(),
                note,
                ride == null ? null : ride.ord(),
                ride == null ? null : ride.count(),
                ride == null ? null : ride.fromOriginMin(),
                ride == null ? null : ride.fromDestMin());
    }

    /**
     * 기점에서 이 정류장까지 <b>노선을 따라</b> 오는 데 몇 분인가.
     *
     * <p><b>왜 순번이 아니라 거리로 나누나</b>: 농어촌버스는 정류장 간격이 들쭉날쭉하다.
     * 읍내에서는 200m 마다 서고 시골 구간은 3km 를 내리 달린다. 순번으로 나누면
     * (12번째 / 69개 → 17%) 읍내 정류장이 실제보다 훨씬 늦게 오는 것으로 나온다.
     * 좌표를 이어 <b>거리 비율</b>로 나누면 그 왜곡이 없다.
     *
     * <p><b>추정이라는 것을 잊지 말 것.</b> 구간마다 속도가 다르고 정차 시간도 들어 있다.
     * 다만 '11:40 ~ 12:25 사이 어딘가' 보다는 훨씬 쓸모 있고, 화면에는 <i>약</i> 을 붙인다.
     *
     * @return 못 구하면 {@code null}(캐시에 없고 이번 요청 예산도 다 썼을 때 포함)
     */
    private Ride rideToStop(String routeId, String stopId, Integer runMin, int[] budget) {
        if (routeId == null || stopId == null || runMin == null) {
            return null;
        }

        List<RouteStopDTO> stops = routeStopsCache.get(routeId);
        if (stops == null) {
            if (budget[0] <= 0) {
                return null;            // 다음 갱신 때 채운다
            }
            budget[0]--;
            try {
                stops = client.routeStops(routeId);
            } catch (RuntimeException e) {
                log.warn("경유 정류장을 받지 못했습니다 (routeId={}): {}", routeId, e.getMessage());
                return null;
            }
            // 빈 결과는 담지 않는다 — 담으면 그 노선이 하루 종일 '정류장 없는 노선'이 된다.
            if (stops.isEmpty()) {
                return null;
            }
            routeStopsCache.put(routeId, stops);
        }

        int at = -1;
        for (int i = 0; i < stops.size(); i++) {
            if (stopId.equals(stops.get(i).stopId())) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return null;                // 이 노선이 이 정류장을 안 지난다(방향이 다른 편성)
        }

        // 정류장을 이은 누적 거리. 마지막 값이 노선 전체 길이다.
        double[] cum = new double[stops.size()];
        for (int i = 1; i < stops.size(); i++) {
            RouteStopDTO p = stops.get(i - 1);
            RouteStopDTO q = stops.get(i);
            cum[i] = cum[i - 1] + haversineM(p.latitude(), p.longitude(), q.latitude(), q.longitude());
        }

        double total = cum[cum.length - 1];
        if (total <= 0) {
            return null;
        }

        double ratio = cum[at] / total;
        int fromOrigin = (int) Math.round(runMin * ratio);
        // 반대 방향은 남은 거리만큼이다. 같은 정류장이라도 오는 쪽에 따라 시각이 다르다.
        int fromDest = (int) Math.round(runMin * (1 - ratio));

        return new Ride(stops.get(at).ord(), stops.size(), fromOrigin, fromDest);
    }

    /** 기점·종점에서 이 정류장까지 각각 몇 분인가. */
    private record Ride(int ord, int count, int fromOriginMin, int fromDestMin) { }

    /** 두 좌표 사이 거리(m). 노선 길이는 수십 km 라 하버사인이면 충분하다. */
    private static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    /**
     * 지금이 운행 시간대인가.
     *
     * <p>첫차와 막차가 <b>같으면</b> 하루 한 편이다. 보은은 그런 노선이 많다.
     * 그때는 그 시각 앞뒤 여유를 조금 준다 — '06:45 한 편'인 노선을 06:46 에 열었다고
     * 이미 끝났다고 잘라 말하면, 아직 안 온 차를 놓쳤다고 알려주는 셈이 된다.
     *
     * <p>막차가 첫차보다 이르면 자정을 넘긴 노선으로 본다(심야). 보은에는 없지만
     * 지역을 갈아탈 때를 위해 넣어 둔다.
     */
    private static boolean isRunning(String first, String last, java.time.LocalTime now) {
        java.time.LocalTime f = java.time.LocalTime.parse(first);
        java.time.LocalTime l = java.time.LocalTime.parse(last);

        if (f.equals(l)) {
            return !now.isBefore(f.minusMinutes(10)) && !now.isAfter(l.plusMinutes(30));
        }
        if (l.isBefore(f)) {                       // 자정을 넘긴다
            return !now.isBefore(f) || !now.isAfter(l);
        }
        return !now.isBefore(f) && !now.isAfter(l);
    }

    /** 지역 전체 노선표. 없으면 한 번 받아 온다. 실패해도 예외를 올리지 않는다. */
    private Map<String, BusRouteDTO> routeHoursTable(IBusClient c) {
        long now = System.currentTimeMillis();
        Map<String, BusRouteDTO> cached = routeHours;

        if (cached != null && now - routeHoursAtMs < ROUTE_HOURS_TTL_MS) {
            return cached;
        }
        try {
            Map<String, BusRouteDTO> loaded = c.allRoutes();
            if (!loaded.isEmpty()) {
                routeHours = loaded;
                routeHoursAtMs = now;
            }
            return routeHours;
        } catch (RuntimeException e) {
            /*
              운행시간은 곁들이는 정보다. 이걸 못 받았다고 도착 안내 전체를 실패시키면
              본래 기능까지 같이 죽는다. 옛 표가 있으면 그거라도 쓴다.
            */
            log.warn("노선 운행시간을 받지 못했습니다: {}", e.getMessage());
            return routeHours;
        }
    }

    /** 제공자가 없으면 그 이유를 그대로 던진다. 빈 결과로 바꾸면 원인이 화면에서 사라진다. */
    private IBusClient require() {
        if (client == null) {
            throw new BusUnavailableException(unavailableReason == null
                    ? "버스 정보를 쓸 수 없습니다." : unavailableReason);
        }
        return client;
    }
}

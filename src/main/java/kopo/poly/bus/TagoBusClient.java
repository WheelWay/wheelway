package kopo.poly.bus;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import kopo.poly.dto.BusArrivalDTO;
import kopo.poly.dto.BusRouteDTO;
import kopo.poly.dto.BusStopDTO;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공공데이터포털 TAGO(전국 버스). 서울을 뺀 지역이 여기로 온다.
 *
 * <p>엔드포인트는 {@code apis.data.go.kr} — <b>포털 자기 서버</b>다. 키 DB 를 직접 보기 때문에
 * 활용신청이 승인되면 곧바로 동작한다. 서울({@code ws.bus.go.kr})이 승인 뒤에도 막혀 있는 것과
 * 다른 점이 이것이다.
 *
 * <p><b>실측으로 확인한 것</b> (2026-08-18 · 보은군 33320)
 * <pre>
 *   근접정류소  getCrdntPrxmtSttnList   보은군청입구 등 7건
 *   도착정보    getSttnAcctoArvlPrearngeInfoList
 *               {"routeno":216, "routetp":"일반버스", "vehicletp":"일반차량",
 *                "arrtime":117, "arrprevstationcnt":2}
 *   경유노선    getSttnThrghRouteList   8개 노선
 * </pre>
 *
 * <p>{@code vehicletp} 가 {@code 저상버스}/{@code 일반차량} 으로 <b>차량 단위</b>로 온다.
 * 이 기능이 성립하는 근거가 이 필드 하나다 — 노선 단위 '저상 운행 노선' 으로는
 * 오지 않는 버스를 기다리게 된다.
 */
@Slf4j
public class TagoBusClient implements IBusClient {

    private static final String BASE = "http://apis.data.go.kr/1613000";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 도착정보는 '지금 몇 분 뒤'라 늦게 오면 값 자체가 틀린다. 길게 잡지 않는다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(6);

    private final HttpClient http;

    /**
     * <b>이미 URL 인코딩된</b> 서비스키. 포털이 주는 '디코딩용 키'를 한 번 인코딩한 값이다.
     *
     * <p>여기서 미리 인코딩해 두는 이유: 쿼리 문자열을 직접 조립해 {@link URI#create(String)} 로
     * 넘기기 때문이다. 빌더에 맡기면 {@code +} {@code /} {@code =} 를 한 번 더 인코딩해
     * 키가 망가진다(그러면 증상이 '키가 틀렸다'로 나와 원인을 엉뚱한 데서 찾게 된다).
     */
    private final String encodedKey;

    /** TAGO 도시코드. {@code getCtyCodeList} 로 확인한다 — 보은군 33320 · 제천시 33030. */
    private final String cityCode;

    public TagoBusClient(String serviceKey, String cityCode) {
        this.encodedKey = URLEncoder.encode(serviceKey, StandardCharsets.UTF_8);
        this.cityCode = cityCode;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    @Override
    public String providerName() {
        return "tago";
    }

    // ------------------------------------------------------------ 정류장

    @Override
    public List<BusStopDTO> nearbyStops(double lat, double lng, int limit) {

        // 이 오퍼레이션에는 반경 파라미터가 없다. 개수로만 끊고, 거리는 우리가 계산한다.
        // cityCode 도 받지 않는다 — 좌표만 보고 전국에서 찾아준다.
        JsonNode body = call("/BusSttnInfoInqireService/getCrdntPrxmtSttnList"
                + "?serviceKey=" + encodedKey
                + "&gpsLati=" + lat
                + "&gpsLong=" + lng
                + "&numOfRows=" + Math.max(limit, 1) * 3   // 거리로 다시 세울 것이라 넉넉히 받는다
                + "&pageNo=1&_type=json");

        List<BusStopDTO> out = new ArrayList<>();
        for (JsonNode it : items(body)) {
            String id = text(it, "nodeid");
            String nm = text(it, "nodenm");
            Double sLat = number(it, "gpslati");
            Double sLng = number(it, "gpslong");
            if (id == null || sLat == null || sLng == null) {
                continue;   // 좌표 없는 행은 지도에 못 얹는다
            }
            out.add(new BusStopDTO(id, nm == null ? "이름 없음" : nm, sLat, sLng,
                    (int) Math.round(distanceM(lat, lng, sLat, sLng))));
        }

        // 제공자가 가까운 순으로 준다는 보장이 문서에 없다. 우리가 잰 거리로 다시 세운다.
        out.sort(Comparator.comparingInt(BusStopDTO::distanceM));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    // ------------------------------------------------------------ 도착정보

    @Override
    public List<BusArrivalDTO> arrivals(String stopId) {

        // ★ 파라미터 이름이 오퍼레이션마다 다르다. 여기는 nodeId(대문자 I), 경유노선은 nodeid 다.
        //   같은 기관 같은 서비스인데 다르다. 틀리면 오류가 아니라 빈 응답이 와서 알아채기 어렵다.
        JsonNode body = call("/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList"
                + "?serviceKey=" + encodedKey
                + "&cityCode=" + cityCode
                + "&nodeId=" + enc(stopId)
                + "&numOfRows=20&pageNo=1&_type=json");

        List<BusArrivalDTO> out = new ArrayList<>();
        for (JsonNode it : items(body)) {
            String vehicleType = text(it, "vehicletp");
            out.add(new BusArrivalDTO(
                    text(it, "routeid"),
                    text(it, "routeno"),
                    text(it, "routetp"),
                    vehicleType,
                    lowFloorOf(vehicleType),
                    intOf(it, "arrtime"),
                    intOf(it, "arrprevstationcnt")));
        }

        // 곧 오는 것부터. 시간이 없는 행은 뒤로 민다(정렬 때문에 없는 값을 0 으로 보면 맨 앞에 온다).
        out.sort(Comparator.comparing(a -> a.arriveSec() == null ? Integer.MAX_VALUE : a.arriveSec()));
        return out;
    }

    /**
     * {@code vehicletp} 원문 → 저상 여부.
     *
     * <p><b>모르면 {@code null} 을 준다.</b> {@code false} 로 채우면 '일반차량이 온다'는
     * 거짓이 되는데, 휠체어 사용자는 이 값 하나로 나갈지 말지를 정한다.
     * 표기가 바뀌거나 새 값이 생기면 조용히 틀리는 대신 '알 수 없음'으로 남는다.
     */
    private static Boolean lowFloorOf(String vehicleType) {
        if (vehicleType == null || vehicleType.isBlank()) {
            return null;
        }
        if (vehicleType.contains("저상")) {
            return Boolean.TRUE;
        }
        if (vehicleType.contains("일반")) {
            return Boolean.FALSE;
        }
        log.warn("TAGO vehicletp 에 모르는 값이 왔습니다: '{}'. 저상 여부를 '알 수 없음'으로 둡니다", vehicleType);
        return null;
    }

    // ------------------------------------------------------------ 경유노선

    @Override
    public List<BusRouteDTO> routesAt(String stopId) {

        // 여기는 nodeid(전부 소문자)다. 위 도착정보의 nodeId 와 다르다 — 오타가 아니다.
        JsonNode body = call("/BusSttnInfoInqireService/getSttnThrghRouteList"
                + "?serviceKey=" + encodedKey
                + "&cityCode=" + cityCode
                + "&nodeid=" + enc(stopId)
                + "&numOfRows=30&pageNo=1&_type=json");

        List<BusRouteDTO> out = new ArrayList<>();
        for (JsonNode it : items(body)) {
            // 이 오퍼레이션은 운행시간을 주지 않는다. 그건 allRoutes() 가 채운다.
            out.add(new BusRouteDTO(
                    text(it, "routeid"),
                    text(it, "routeno"),
                    text(it, "routetp"),
                    text(it, "startnodenm"),
                    text(it, "endnodenm"),
                    null, null, null));
        }
        return out;
    }

    // ------------------------------------------------------------ 노선별 경유 정류장

    @Override
    public List<kopo.poly.dto.RouteStopDTO> routeStops(String routeId) {

        // 여기는 routeId(대문자 I)다. 오퍼레이션마다 파라미터 이름이 달라서 매번 확인해야 한다.
        JsonNode body = call("/BusRouteInfoInqireService/getRouteAcctoThrghSttnList"
                + "?serviceKey=" + encodedKey
                + "&cityCode=" + cityCode
                + "&routeId=" + enc(routeId)
                + "&numOfRows=500&pageNo=1&_type=json");

        List<kopo.poly.dto.RouteStopDTO> out = new ArrayList<>();
        for (JsonNode it : items(body)) {
            Integer ord = intOf(it, "nodeord");
            String id = text(it, "nodeid");
            Double lat = number(it, "gpslati");
            Double lng = number(it, "gpslong");

            // 순번이나 좌표가 없으면 쓸 데가 없다 — 이 값의 쓸모가 '순서'와 '거리'다.
            if (ord == null || id == null || lat == null || lng == null) {
                continue;
            }
            out.add(new kopo.poly.dto.RouteStopDTO(ord, id, text(it, "nodenm"), lat, lng));
        }

        // 순번대로 세워 둔다. 응답이 정렬돼 오지만 기대지 않는다 — 어긋나면 소요시간이 통째로 틀어진다.
        out.sort(java.util.Comparator.comparingInt(kopo.poly.dto.RouteStopDTO::ord));
        return out;
    }

    // ------------------------------------------------------------ 전체 정류장

    @Override
    public List<BusStopDTO> allStops() {

        List<BusStopDTO> out = new ArrayList<>();

        /*
          페이지를 나눠 받는다. 보은은 838곳이라 한 쪽(1000)이면 끝나지만,
          지역을 갈아타면 더 많을 수 있어 끝까지 도는 구조로 둔다.
          쪽수 상한을 두는 이유: totalCount 가 이상하게 오면 무한히 돌 수 있다.
        */
        for (int page = 1; page <= 20; page++) {
            JsonNode body = call("/BusSttnInfoInqireService/getSttnNoList"
                    + "?serviceKey=" + encodedKey
                    + "&cityCode=" + cityCode
                    + "&numOfRows=1000&pageNo=" + page + "&_type=json");

            List<JsonNode> items = items(body);
            for (JsonNode it : items) {
                String id = text(it, "nodeid");
                Double lat = number(it, "gpslati");
                Double lng = number(it, "gpslong");
                if (id == null || lat == null || lng == null) {
                    continue;   // 좌표 없는 행은 지도에 못 얹는다
                }
                String nm = text(it, "nodenm");
                out.add(new BusStopDTO(id, nm == null ? "이름 없음" : nm, lat, lng, 0));
            }

            Integer total = intOf(body, "totalCount");
            if (items.isEmpty() || total == null || out.size() >= total) {
                break;
            }
        }

        log.info("TAGO 정류장 목록 {}곳 (cityCode={})", out.size(), cityCode);
        return out;
    }

    // ------------------------------------------------------------ 전체 노선·운행시간

    @Override
    public java.util.Map<String, BusRouteDTO> allRoutes() {

        // numOfRows 를 크게 잡아 한 번에 받는다. 보은은 85개라 한 쪽이면 끝난다.
        JsonNode body = call("/BusRouteInfoInqireService/getRouteNoList"
                + "?serviceKey=" + encodedKey
                + "&cityCode=" + cityCode
                + "&numOfRows=1000&pageNo=1&_type=json");

        java.util.Map<String, BusRouteDTO> out = new java.util.LinkedHashMap<>();
        for (JsonNode it : items(body)) {
            String id = text(it, "routeid");
            if (id == null) {
                continue;
            }
            out.put(id, new BusRouteDTO(
                    id,
                    text(it, "routeno"),
                    text(it, "routetp"),
                    text(it, "startnodenm"),
                    text(it, "endnodenm"),
                    hhmm(text(it, "startvehicletime")),
                    hhmm(text(it, "endvehicletime")),
                    null));
        }
        log.info("TAGO 노선 목록 {}개 (cityCode={})", out.size(), cityCode);
        return out;
    }

    /**
     * {@code 1900} · {@code "0700"} → {@code "19:00"} · {@code "07:00"}.
     *
     * <p>★ 같은 필드가 <b>숫자로도 문자열로도</b> 온다. 앞자리가 0 이면 JSON 문자열
     * ({@code "0700"}), 아니면 숫자({@code 1900})다. 숫자로 받으면 앞의 0 이 날아가
     * {@code 645} 같은 값이 되므로 네 자리로 되채워야 한다.
     */
    private static String hhmm(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty() || digits.length() > 4) {
            return null;
        }
        String p = "0".repeat(4 - digits.length()) + digits;
        return p.substring(0, 2) + ":" + p.substring(2);
    }

    // ------------------------------------------------------------ 호출·파싱

    /**
     * 한 번 부르고 {@code response.body} 를 돌려준다.
     *
     * <p>실패는 전부 {@link BusUnavailableException} 이다. 빈 목록으로 바꾸지 않는다 —
     * 못 부른 것과 '오는 버스가 없는 것'은 사용자에게 전혀 다른 뜻이다.
     */
    private JsonNode call(String pathAndQuery) {

        String url = BASE + pathAndQuery;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();

        String raw;
        try {
            HttpResponse<String> res =
                    http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                throw new BusUnavailableException("버스 정보 서버가 HTTP " + res.statusCode() + " 를 냈습니다.");
            }
            raw = res.body();
        } catch (IOException e) {
            throw new BusUnavailableException("버스 정보 서버에 닿지 못했습니다.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusUnavailableException("버스 정보 조회가 중단됐습니다.", e);
        }

        /*
          ★ _type=json 을 붙여도 JSON 이 아닐 때가 있다.
          키가 등록돼 있지 않거나 트래픽을 넘기면 게이트웨이가 앞단에서 XML 봉투를 돌려준다
          (<OpenAPI_ServiceResponse><errMsg>...). 그대로 파싱하면 엉뚱한 예외가 나서
          '응답 형식이 틀렸다'는 원인이 묻힌다. 여기서 먼저 가른다.
        */
        String head = raw.stripLeading();
        if (!head.startsWith("{")) {
            log.error("TAGO 가 JSON 이 아닌 응답을 냈습니다: {}",
                    head.substring(0, Math.min(300, head.length())));
            throw new BusUnavailableException("버스 정보 서버가 오류를 돌려줬습니다. 서비스키·호출량을 확인하세요.");
        }

        JsonNode root = MAPPER.readTree(raw).path("response");
        String code = text(root.path("header"), "resultCode");
        if (code != null && !"00".equals(code)) {
            String msg = text(root.path("header"), "resultMsg");
            throw new BusUnavailableException("버스 정보 조회 실패 (" + code + " " + msg + ")");
        }
        return root.path("body");
    }

    /**
     * {@code body.items.item} 을 항상 목록으로 만든다.
     *
     * <p><b>세 가지 모양으로 온다.</b> 이걸 모르면 '가끔 터지는' 버그가 된다.
     * <pre>
     *   여러 건  "items":{"item":[{...},{...}]}     배열
     *   한 건    "items":{"item":{...}}             ★ 배열이 아니라 객체다
     *   0 건     "items":""  또는 items 자체가 없음  ★ 빈 배열이 아니다
     * </pre>
     * 보은은 배차가 드물어 <b>한 건 / 0 건이 오히려 흔하다.</b>
     */
    private static List<JsonNode> items(JsonNode body) {
        JsonNode item = body.path("items").path("item");
        if (item.isArray()) {
            List<JsonNode> out = new ArrayList<>(item.size());
            item.forEach(out::add);
            return out;
        }
        return item.isObject() ? List.of(item) : List.of();
    }

    /** 없거나 빈 값이면 {@code null}. 빈 문자열을 그대로 올리면 화면에 빈 칸이 생긴다. */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asString();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static Integer intOf(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double number(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null) {
            return null;
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** 두 좌표 사이 거리(m). 정류장이 수백 m 안에 있어 하버사인이면 충분하다. */
    private static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

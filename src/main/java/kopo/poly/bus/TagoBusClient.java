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

    /**
     * <b>오래 쓰는 값</b>을 받을 때 몇 번까지 다시 시도할지.
     *
     * <h3>왜 필요한가</h3>
     * TAGO 는 멀쩡한 요청에도 무작위로 실패한다. 두 얼굴이 있다.
     * <pre>
     *   HTTP_ERROR / 04     게이트웨이 일반 오류
     *   99 (30/30)          "가용한 세션이 존재하지 않습니다"
     * </pre>
     *
     * <p><b>우리가 너무 자주 불러서가 아니다.</b> 2026-08-24 에 앱을 끄고 재봤다 —
     * <b>간격 없이 연속 45번을 쏴도 한 번도 안 막혔고</b>, 3초·6초 간격에서는 각각 19/20 이었다.
     * 열려 있던 연결도 1개뿐이었다. 즉 실패는 우리 호출 속도와 무관하고,
     * 그 API 를 쓰는 <b>전체가 공유하는</b> 무언가에 걸린다. 시간대에 따라 심해진다
     * (같은 날 아침 28% → 오전 5%).
     *
     * <p>그러니 <b>대응은 재시도뿐이다.</b> 실패가 무작위 5% 라면 네 번 시도로 0.001% 가 된다.
     *
     * <h3>어디에 쓰고 어디에 안 쓰나</h3>
     * <pre>
     *   쓴다     정류장 목록 · 경유 정류장 · 노선 목록
     *            — 하루~여섯 시간에 한 번 받고 그 결과로 그 시간을 산다.
     *              한 번 빠지면 그동안 '없는 정류장'·'없는 노선'이 된다
     *   안 쓴다  도착정보
     *            — '지금 몇 분 뒤'라 늦으면 값 자체가 틀리고,
     *              실패해도 다음 갱신(45초)에서 곧 다시 받는다
     * </pre>
     */
    private static final int BULK_TRIES = 4;

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

        /*
          ★ 다시 시도한다. 저상 정류장 표가 이 호출 118번으로 만들어지는데,
          한 번씩 튕길 때마다 그 노선이 통째로 빠진다 — 그 노선만 다니는 정류장은
          '저상이 안 서는 곳'이 되고, 복합 경로가 그만큼 답을 못 낸다.
          실제로 2026-08-24 아침에 표가 며칠째 미완성이던 원인이 이것이었다.
        */
        // 여기는 routeId(대문자 I)다. 오퍼레이션마다 파라미터 이름이 달라서 매번 확인해야 한다.
        JsonNode body = callRetry("/BusRouteInfoInqireService/getRouteAcctoThrghSttnList"
                + "?serviceKey=" + encodedKey
                + "&cityCode=" + cityCode
                + "&routeId=" + enc(routeId)
                + "&numOfRows=500&pageNo=1&_type=json", BULK_TRIES);

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
        Integer expected = null;

        for (int page = 1; page <= 20; page++) {
            /*
              ★ 쪽마다 다시 시도한다.

              TAGO 는 멀쩡한 요청에도 이따금 HTTP_ERROR/04 를 낸다 — 쪽 번호나 개수와
              무관하게 무작위다(2026-08-22 실측: 같은 요청이 6번 중 2번 실패, 재시도하면 성공).

              전에는 한 쪽이 실패하면 그 자리에서 멈추고 <b>거기까지 받은 것을 정상인 양
              돌려줬다.</b> 청주는 세 쪽이라 첫 쪽만 받고 끝나는 일이 잦았고, 그러면
              1,000곳만 아는 채로 하루를 돈다 — 지도에서 나머지 1,709곳이 사라진다.
              증상이 '정류장이 안 찍힌다' 라서 원인을 찾기 어렵다.
            */
            JsonNode body;
            try {
                body = callRetry("/BusSttnInfoInqireService/getSttnNoList"
                        + "?serviceKey=" + encodedKey
                        + "&cityCode=" + cityCode
                        + "&numOfRows=1000&pageNo=" + page + "&_type=json", BULK_TRIES);
            } catch (BusUnavailableException e) {
                /*
                  끝까지 실패하면 <b>부분 목록을 돌려주지 않고 던진다.</b>
                  부르는 쪽(BusService)이 빈 결과만 안 담고 부분 결과는 담아 버려서,
                  한 번 모자라게 받으면 그대로 굳는다. 아예 안 받은 것으로 두면
                  다음 요청이 다시 받는다.
                */
                log.warn("정류장 목록 {}쪽을 {}번 시도했지만 못 받았습니다. 이번 적재는 버립니다.",
                        page, BULK_TRIES);
                throw e;
            }

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
            if (total != null) {
                expected = total;
            }
            if (items.isEmpty() || total == null || out.size() >= total) {
                break;
            }
        }

        /*
          ★ 다 못 받았으면 받은 것도 쓰지 않는다.

          모자란 목록은 '없는 정류장' 을 만든다. 빈 목록이면 부르는 쪽이 안 담고 다시
          받지만(BusService.allStopsCached), 1,000곳짜리 부분 목록은 <b>정상으로 보여서
          그대로 캐시되고 하루를 간다.</b> 조용히 틀린 답보다 오류가 낫다.
        */
        if (expected != null && out.size() < expected) {
            log.warn("정류장 목록이 모자랍니다 · {}곳 / 전체 {}곳 (cityCode={}). 이번 적재는 버립니다.",
                    out.size(), expected, cityCode);
            throw new BusUnavailableException(
                    "정류장 목록을 다 받지 못했습니다 (" + out.size() + "/" + expected + ").");
        }

        log.info("TAGO 정류장 목록 {}곳 (cityCode={})", out.size(), cityCode);
        return out;
    }

    // ------------------------------------------------------------ 전체 노선·운행시간

    @Override
    public java.util.Map<String, BusRouteDTO> allRoutes() {

        // numOfRows 를 크게 잡아 한 번에 받는다. 보은은 85개라 한 쪽이면 끝난다.
        // ★ 여기가 실패하면 저상 표 만들기가 시작조차 못 한다. 다시 시도한다.
        JsonNode body = callRetry("/BusRouteInfoInqireService/getRouteNoList"
                + "?serviceKey=" + encodedKey
                + "&cityCode=" + cityCode
                + "&numOfRows=1000&pageNo=1&_type=json", BULK_TRIES);

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

        JsonNode parsed = MAPPER.readTree(raw);

        /*
          ★ 오류인데 JSON 으로 오는 형태가 하나 더 있다.

            {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"HTTP_ERROR","returnReasonCode":"04"}}}

          게이트웨이가 앞단에서 내는 봉투인데 <b>중괄호로 시작해서</b> 위의 'JSON 이 아님'
          가드를 그냥 통과한다. 그리고 response.header.resultCode 가 아예 없으니
          아래 코드 검사도 통과해서, 결국 <b>빈 body 가 정상 응답처럼 흘러나갔다.</b>

          그 결과가 '데이터 없음' 이다 — 정류장 목록을 받다가 이걸 만나면 그 쪽이 통째로
          비고, 화면은 '그 동네에는 정류장이 없다' 고 조용히 거짓말한다.
          실제로 그렇게 됐다(2026-08-22 · 청주 2,709곳 중 1,000곳만 올라왔다).

          못 부른 것과 '없는 것'은 다르다는 이 클래스의 약속을 여기서도 지킨다.
        */
        JsonNode err = parsed.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        if (!err.isMissingNode()) {
            String em = text(err, "errMsg");
            String rc = text(err, "returnReasonCode");
            throw new BusUnavailableException(
                    "버스 정보 서버가 오류를 돌려줬습니다 (" + em + " " + rc + ").");
        }

        JsonNode root = parsed.path("response");
        if (root.isMissingNode()) {
            throw new BusUnavailableException("버스 정보 서버가 알 수 없는 형식으로 답했습니다.");
        }
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

    /**
     * 실패하면 다시 부른다. {@link #BULK_TRIES} 참고 — <b>오래 쓰는 값에만</b> 쓴다.
     *
     * <p>마지막 예외를 그대로 올린다. 부르는 쪽이 '몇 번 해봤는데도 안 됐다'를 알고
     * 그 다음을 정해야 한다 — 정류장 목록은 통째로 버리고, 경유 정류장은 그 노선만 건너뛴다.
     */
    private JsonNode callRetry(String pathAndQuery, int tries) {
        BusUnavailableException last = null;

        for (int t = 1; t <= tries; t++) {
            try {
                return call(pathAndQuery);
            } catch (BusUnavailableException e) {
                last = e;
                if (t < tries) {
                    // 조금씩 늘려 쉰다. 상대가 밀릴 때 같은 박자로 두드리면 같이 밀린다.
                    sleepQuietly(300L * t);
                }
            }
        }
        throw last;
    }

    /** 다시 부르기 전 잠깐 쉰다. 몰아치면 오히려 오류가 는다. */
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

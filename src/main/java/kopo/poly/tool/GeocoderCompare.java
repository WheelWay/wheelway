package kopo.poly.tool;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 카카오 vs 네이버 지오코딩 비교 도구.
 *
 * <p>같은 공사 주소를 양쪽으로 돌려 <b>어느 쪽 좌표가 실제 보도에 더 가까운지</b> 잰다.
 * 이 프로젝트에서 계속 문제가 된 것이 "지오코딩 좌표가 필지 중심에 찍혀 보도와 멀다" 였고,
 * 그 거리가 곧 사람이 손으로 고쳐야 할 양이기 때문이다.
 *
 * <p><b>DB 를 건드리지 않는다.</b> 읽기만 하고 결과를 표로 찍는다.
 * 어느 쪽을 쓸지는 이 표를 보고 사람이 정한다.
 *
 * <h3>판단 지표</h3>
 * <ul>
 *   <li><b>가장 가까운 보도까지의 거리</b> — 작을수록 좋다. 이게 결론을 낸다</li>
 *   <li>반경 5m 안에 보도가 있는 건수 — 차선책 없이 바로 매칭되는 수</li>
 *   <li>두 결과 사이의 거리 — 같은 주소를 얼마나 다르게 보는지</li>
 *   <li>실패 건수 — 못 찾는 주소</li>
 * </ul>
 *
 * <h3>실행 방법</h3>
 * <pre>
 *   // 서버(localhost:8080)가 떠 있어야 한다. 거리 계산을 서버 그래프에 물어보기 때문이다.
 *   GeocoderCompare
 *   GeocoderCompare --csv=crawl-output/roaddig_중구_보도_20260805.csv
 * </pre>
 *
 * <p>키는 {@code credentials/api_keys.properties} 에서 읽는다 —
 * {@code kakao.rest-api-key}, {@code naver.client-id}, {@code naver.client-secret}.
 */
public final class GeocoderCompare {

    private static final String DEFAULT_CSV = "crawl-output/roaddig_중구_보도_20260805.csv";
    private static final String ADDRESS_PREFIX = "서울 중구 ";

    private static final String PROPS = "src/main/resources/application.properties";
    private static final String LOCAL_PROPS = "credentials/api_keys.properties";

    private static final String KAKAO_API = "https://dapi.kakao.com/v2/local/search/address.json";

    /** NCP 지오코딩. 게이트웨이 주소가 바뀐 이력이 있어 순서대로 시도한다. */
    private static final String[] NAVER_APIS = {
            "https://naveropenapi.apigw.ntruss.com/map-geocode/v2/geocode",
            "https://maps.apigw.ntruss.com/map-geocode/v2/geocode"};

    /** 거리 판정을 물어볼 서버. 그래프가 거기 올라가 있다. */
    private static final String PREVIEW_API = "http://localhost:8080/api/construction/preview";

    private static final long SLEEP_MS = 300L;
    private static final int TIMEOUT_SEC = 15;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private GeocoderCompare() {
    }

    /** 한 주소에 대한 양쪽 결과. 실패면 좌표가 NaN 이다. */
    private record Result(String query, double kakaoLat, double kakaoLon,
                          double naverLat, double naverLon) {

        boolean kakaoOk() {
            return !Double.isNaN(kakaoLat);
        }

        boolean naverOk() {
            return !Double.isNaN(naverLat);
        }
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        Map<String, String> opt = parseOptions(args);

        Properties props = readProperties();
        String kakaoKey = require(props, "kakao.rest-api-key");
        String naverId = require(props, "naver.client-id");
        String naverSecret = require(props, "naver.client-secret");
        if (kakaoKey == null || naverId == null || naverSecret == null) {
            return;
        }

        List<String> queries = readAddresses(Paths.get(opt.getOrDefault("csv", DEFAULT_CSV)));
        System.out.println("[compare] 주소 " + queries.size() + "건");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SEC)).build();

        String naverApi = pickNaverApi(client, naverId, naverSecret);
        if (naverApi == null) {
            System.out.println("[중단] 네이버 지오코딩에 연결하지 못했습니다. 키와 Geocoding 활성화를 확인하세요.");
            return;
        }
        System.out.println("[compare] 네이버 엔드포인트 " + naverApi);
        System.out.println();

        List<Result> results = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            String q = queries.get(i);
            double[] k = geocodeKakao(client, kakaoKey, q);
            Thread.sleep(SLEEP_MS);
            double[] n = geocodeNaver(client, naverApi, naverId, naverSecret, q);
            Thread.sleep(SLEEP_MS);

            results.add(new Result(q, k[0], k[1], n[0], n[1]));
            System.out.printf("  [%2d/%2d] %s  카카오 %s / 네이버 %s%n",
                    i + 1, queries.size(), q,
                    Double.isNaN(k[0]) ? "실패" : "ok", Double.isNaN(n[0]) ? "실패" : "ok");
        }

        report(client, results);
    }

    // ------------------------------------------------------------------ 보고

    private static void report(HttpClient client, List<Result> results) throws Exception {
        System.out.println();
        System.out.println("=== 가장 가까운 보도까지의 거리 (서버 그래프 기준) ===");

        List<Double> kakaoNear = new ArrayList<>();
        List<Double> naverNear = new ArrayList<>();
        List<String> naverBetter = new ArrayList<>();
        List<String> kakaoBetter = new ArrayList<>();
        double gapSum = 0;
        int gapCount = 0;

        for (Result r : results) {
            double kd = r.kakaoOk() ? nearestM(client, r.kakaoLat(), r.kakaoLon()) : Double.NaN;
            double nd = r.naverOk() ? nearestM(client, r.naverLat(), r.naverLon()) : Double.NaN;

            if (!Double.isNaN(kd)) {
                kakaoNear.add(kd);
            }
            if (!Double.isNaN(nd)) {
                naverNear.add(nd);
            }
            if (!Double.isNaN(kd) && !Double.isNaN(nd)) {
                gapSum += haversineM(r.kakaoLat(), r.kakaoLon(), r.naverLat(), r.naverLon());
                gapCount++;
                if (nd + 1 < kd) {
                    naverBetter.add(String.format("%-28s 카카오 %5.1fm → 네이버 %5.1fm", r.query(), kd, nd));
                } else if (kd + 1 < nd) {
                    kakaoBetter.add(String.format("%-28s 네이버 %5.1fm → 카카오 %5.1fm", r.query(), nd, kd));
                }
            }
        }

        line("카카오", results.size() - countOk(results, true), kakaoNear);
        line("네이버", results.size() - countOk(results, false), naverNear);

        if (gapCount > 0) {
            System.out.printf("%n두 결과 사이의 평균 거리  %.1fm%n", gapSum / gapCount);
        }

        System.out.println();
        System.out.println("네이버가 더 가까운 건 " + naverBetter.size()
                + "건 / 카카오가 더 가까운 건 " + kakaoBetter.size() + "건 (1m 초과 차이만)");

        print("--- 네이버가 나은 경우 ---", naverBetter);
        print("--- 카카오가 나은 경우 ---", kakaoBetter);

        System.out.println();
        System.out.println("※ '가장 가까운 보도까지의 거리'가 작을수록 손댈 일이 적습니다.");
        System.out.println("   5m 이내면 반경 매칭이 바로 되고, 그보다 멀면 차선책이나 수동 수정이 필요합니다.");
    }

    private static void line(String label, int failed, List<Double> near) {
        if (near.isEmpty()) {
            System.out.printf("  %-6s 성공 0건%n", label);
            return;
        }
        List<Double> sorted = new ArrayList<>(near);
        sorted.sort(Double::compare);

        System.out.printf("  %-6s 성공 %2d / 실패 %d  |  중앙값 %5.1fm  평균 %5.1fm  최대 %5.1fm"
                        + "  |  5m 이내 %2d건, 15m 초과 %2d건%n",
                label, near.size(), failed,
                sorted.get(sorted.size() / 2),
                near.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                sorted.get(sorted.size() - 1),
                near.stream().filter(d -> d <= 5).count(),
                near.stream().filter(d -> d > 15).count());
    }

    private static void print(String title, List<String> rows) {
        if (rows.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println(title);
        rows.stream().limit(12).forEach(s -> System.out.println("  " + s));
    }

    private static int countOk(List<Result> rs, boolean kakao) {
        return (int) rs.stream().filter(r -> kakao ? r.kakaoOk() : r.naverOk()).count();
    }

    /** 서버에 물어본다. 그래프가 거기 있으므로 판정 기준이 실제 동작과 같아진다. */
    private static double nearestM(HttpClient client, double lat, double lon) throws Exception {
        String uri = PREVIEW_API + "?lat=" + lat + "&lng=" + lon;
        HttpRequest req = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(TIMEOUT_SEC)).GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            return Double.NaN;
        }
        double d = MAPPER.readTree(res.body()).get("nearestM").asDouble();
        return (d < 0) ? Double.NaN : d;
    }

    // -------------------------------------------------------------- 지오코딩

    private static double[] geocodeKakao(HttpClient client, String key, String query) throws Exception {
        String uri = KAKAO_API + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(uri))
                .header("Authorization", "KakaoAK " + key)
                .timeout(Duration.ofSeconds(TIMEOUT_SEC)).GET().build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            return new double[]{Double.NaN, Double.NaN};
        }
        JsonNode docs = MAPPER.readTree(res.body()).get("documents");
        if (docs == null || docs.isEmpty()) {
            return new double[]{Double.NaN, Double.NaN};
        }
        JsonNode f = docs.get(0);
        return new double[]{Double.parseDouble(f.get("y").asString()),
                Double.parseDouble(f.get("x").asString())};
    }

    /** NCP 지오코딩. 응답의 {@code x} 가 경도, {@code y} 가 위도다(카카오와 같은 규칙). */
    private static double[] geocodeNaver(HttpClient client, String api, String id, String secret, String query)
            throws Exception {

        String uri = api + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(uri))
                .header("X-NCP-APIGW-API-KEY-ID", id)
                .header("X-NCP-APIGW-API-KEY", secret)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(TIMEOUT_SEC)).GET().build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            return new double[]{Double.NaN, Double.NaN};
        }
        JsonNode addresses = MAPPER.readTree(res.body()).get("addresses");
        if (addresses == null || addresses.isEmpty()) {
            return new double[]{Double.NaN, Double.NaN};
        }
        JsonNode f = addresses.get(0);
        return new double[]{Double.parseDouble(f.get("y").asString()),
                Double.parseDouble(f.get("x").asString())};
    }

    /** 게이트웨이 주소가 바뀐 이력이 있어, 실제로 200 이 오는 쪽을 골라 쓴다. */
    private static String pickNaverApi(HttpClient client, String id, String secret) {
        for (String api : NAVER_APIS) {
            try {
                double[] r = geocodeNaver(client, api, id, secret, ADDRESS_PREFIX + "신당동 850-2");
                if (!Double.isNaN(r[0])) {
                    return api;
                }
                System.out.println("[안내] " + api + " — 응답은 왔으나 결과가 없습니다.");
            } catch (Exception e) {
                System.out.println("[안내] " + api + " — " + e.getMessage());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 입력

    /** {@code ConstructionZoneLoader} 와 같은 기준으로 적재 대상 주소만 뽑는다. */
    private static List<String> readAddresses(Path csv) throws Exception {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        String jimok = "대도전답임구학원장묘사수유목과천잡";

        for (int i = 1; i < lines.size(); i++) {
            // 공사명에 쉼표가 들어있는 행이 있다("2026년 중구,용산구 관내...").
            // 따옴표를 먼저 지우고 자르면 컬럼이 밀리므로 따옴표를 인식하며 잘라야 한다.
            String[] cols = splitCsv(lines.get(i));
            if (cols.length < 2) {
                continue;
            }
            String[] ends = cols[1].split("~");
            String from = ends[0].trim();
            if (ends.length > 1 && !from.equals(ends[1].trim())) {
                continue;   // 구간은 좌표 한 쌍으로 표현할 수 없다
            }
            String q = from.replaceAll("(\\d+(?:-\\d+)?)\\s*[" + jimok + "]\\s*$", "$1");
            if (!q.matches(".*\\d+(-\\d+)?$")) {
                continue;   // 번지가 없으면 한 점으로 특정할 수 없다
            }
            out.add(ADDRESS_PREFIX + q);
        }
        return out;
    }

    /** 큰따옴표로 감싼 CSV 한 줄을 자른다. 값 안의 쉼표를 컬럼 구분자로 오인하지 않는다. */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;

        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out.toArray(new String[0]);
    }

    private static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double r1 = Math.toRadians(lat1);
        double r2 = Math.toRadians(lat2);
        double dLat = r2 - r1;
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(r1) * Math.cos(r2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6_371_008.8 * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key, "").trim();
        if (v.isEmpty()) {
            System.out.println("[중단] " + key + " 가 비어 있습니다. " + LOCAL_PROPS + " 를 확인하세요.");
            return null;
        }
        return v;
    }

    private static Properties readProperties() {
        Properties props = new Properties();
        for (String path : new String[]{PROPS, LOCAL_PROPS}) {
            Path file = Paths.get(path);
            if (!Files.exists(file)) {
                continue;
            }
            try (var in = Files.newInputStream(file)) {
                Properties loaded = new Properties();
                loaded.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                props.putAll(loaded);
            } catch (Exception e) {
                System.out.println("[경고] " + path + " 를 읽지 못했습니다: " + e.getMessage());
            }
        }
        return props;
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> opt = new HashMap<>();
        for (String a : Arrays.asList(args)) {
            if (a.startsWith("--")) {
                int eq = a.indexOf('=');
                if (eq > 2) {
                    opt.put(a.substring(2, eq).trim(), a.substring(eq + 1).trim());
                } else {
                    opt.put(a.substring(2).trim(), "true");
                }
            }
        }
        return opt;
    }
}

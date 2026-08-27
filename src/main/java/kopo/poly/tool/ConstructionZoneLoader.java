package kopo.poly.tool;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공사구간 CSV → 카카오 지오코딩 → CONSTRUCTION_ZONES 적재 도구.
 *
 * <p>{@link RoaddigCrawler} 가 뽑아둔 '도로구분=보도, 미종료' 공고를 좌표까지 붙여 DB 에 넣는다.
 * {@link OsmGraphLoader} 와 마찬가지로 Spring Bean 이 아니고 main 메서드로만 실행한다.
 *
 * <h3>왜 전부 다 넣지 않는가</h3>
 * 크롤링 원문의 '공사구간'은 형식이 고르지 않다. 좌표 하나로 정확히 찍을 수 있는 것만 넣는다.
 * 하드필터는 <b>반경 안의 엣지를 통행 불가로 만드는</b> 동작이라, 좌표가 부정확하면
 * 멀쩡한 길을 막아버린다. 못 넣는 것을 억지로 넣는 쪽이 안 넣는 쪽보다 나쁘다.
 *
 * <p>중구 67건 기준으로 <b>60건이 적재 대상</b>이고 7건이 제외된다.
 * <ul>
 *   <li><b>번지 없음 4건</b> — {@code 중구 동호로}, {@code 중구 마른내로}, {@code 중구 다산로},
 *       {@code 중구 남대문로5가}. 도로나 동 전체를 가리켜 한 점으로 특정할 수 없다.
 *       지오코딩하면 도로 대표점이 나오는데 실제 공사 위치와 수백 m 떨어질 수 있다</li>
 *   <li><b>구간 3건</b> — 시작과 끝 주소가 다르다. CONSTRUCTION_ZONES 는 좌표가 한 쌍뿐이라
 *       구간을 표현할 수 없다. 그중 {@code 신당동 247-14 ~ 신당동-14} 는 원본 자체가 깨져 있다</li>
 * </ul>
 *
 * <h3>지목 표기 처리</h3>
 * {@code 황학동 1104대}, {@code 신당동 140도} 처럼 번지 뒤에 지목(대지·도로)이 한 글자 붙는 경우가 있다.
 * 카카오 주소검색은 이 글자가 붙으면 못 찾으므로 떼고 보낸다. <b>번지가 없는 것과 혼동하지 말 것</b> —
 * 이건 번지가 있는 정상 주소다.
 *
 * <h3>실행 방법</h3>
 * <pre>
 *   // CSV 파싱·필터만. API 도 DB 도 안 건드린다. 무엇이 들어가고 무엇이 빠지는지 먼저 확인할 때
 *   ConstructionZoneLoader dry-run
 *
 *   // 지오코딩만 하고 결과를 출력. DB 에 안 넣는다
 *   ConstructionZoneLoader geocode
 *
 *   // 지오코딩 + 적재
 *   ConstructionZoneLoader load
 *   ConstructionZoneLoader load --force    // 같은 region 기존 행을 지우고 다시
 * </pre>
 *
 * <p>카카오 REST 키는 {@code credentials/api_keys.properties} 의 {@code kakao.rest-api-key} 에서 읽는다.
 */
public final class ConstructionZoneLoader {

    private static final String DEFAULT_CSV = "crawl-output/roaddig_중구_보도_20260805.csv";
    private static final String DEFAULT_REGION = "seoul-junggu";

    /** 지번 앞에 붙일 상위 주소. CSV 원문에 시/구가 없는 행이 많아 그대로 보내면 다른 구가 잡힌다. */
    private static final String ADDRESS_PREFIX = "서울 중구 ";

    private static final String PROPS = "src/main/resources/application.properties";
    private static final String LOCAL_PROPS = "credentials/api_keys.properties";
    private static final String KEY_KAKAO = "kakao.rest-api-key";

    private static final String KAKAO_ADDRESS_API = "https://dapi.kakao.com/v2/local/search/address.json";

    /**
     * 호출 간 대기(ms). 카카오 로컬 API 는 하루 10만 건 무료지만 순간 호출량 제한이 있다.
     * 60건이면 이 값으로도 1분 남짓이라 굳이 줄일 이유가 없다.
     */
    private static final long SLEEP_MS = 300L;

    private static final int TIMEOUT_SEC = 15;

    /**
     * 적재한 좌표가 여기를 벗어나면 경고한다. OSM bbox 와 같은 범위다.
     * 동명이 다른 구에도 있어서(예: 신당동) 엉뚱한 곳이 잡히는 것을 잡아내는 안전장치다.
     */
    private static final double BBOX_MIN_LAT = 37.54163;
    private static final double BBOX_MAX_LAT = 37.57582;
    private static final double BBOX_MIN_LON = 126.96111;
    private static final double BBOX_MAX_LON = 127.02559;

    /** 번지 뒤에 붙는 지목 한 글자. 대(대지)·도(도로)가 대부분이다. */
    private static final String JIMOK = "대도전답임구천잡학원장묘사수유목과";

    /** {@code 850-2} / {@code 1104} 로 끝나는가 = 번지가 있는가. */
    private static final Pattern BUNJI = Pattern.compile("\\d+(-\\d+)?$");

    /** 끝에 붙은 지목 한 글자를 떼기 위한 것. */
    private static final Pattern JIMOK_TAIL = Pattern.compile("(\\d+(?:-\\d+)?)\\s*[" + JIMOK + "]\\s*$");

    /** 공사기간 원본 형식: {@code 2026.07.28~2026.08.31} */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ConstructionZoneLoader() {
    }

    // ------------------------------------------------------------------ 자료형

    /** CSV 한 줄에서 뽑아낸 것. {@code query} 는 카카오에 보낼 정규화된 주소다. */
    private record Zone(String name, String roadSegment, String query,
                        LocalDate startDate, LocalDate endDate) {
    }

    /** 지오코딩 결과. {@code lat} 이 NaN 이면 실패다. */
    private record Located(Zone zone, double lat, double lon, String matchedAddress) {

        boolean failed() {
            return Double.isNaN(lat);
        }

        boolean outsideBbox() {
            return !failed() && (lat < BBOX_MIN_LAT || lat > BBOX_MAX_LAT
                    || lon < BBOX_MIN_LON || lon > BBOX_MAX_LON);
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        String mode = (args.length > 0 && !args[0].startsWith("--")) ? args[0] : "dry-run";
        Map<String, String> opt = parseOptions(args);

        switch (mode) {
            case "dry-run" -> dryRun(opt);
            case "geocode" -> geocodeOnly(opt);
            case "load" -> load(opt);
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("사용법: ConstructionZoneLoader [dry-run|geocode|load] [옵션]");
        System.out.println("  dry-run  (기본) CSV 파싱·필터만. API/DB 안 건드림");
        System.out.println("  geocode        지오코딩까지만. DB 안 건드림");
        System.out.println("  load           지오코딩 + CONSTRUCTION_ZONES 적재");
        System.out.println();
        System.out.println("  --csv=" + DEFAULT_CSV);
        System.out.println("  --region=" + DEFAULT_REGION);
        System.out.println("  --force        같은 region 기존 행을 지우고 다시 적재");
    }

    // --------------------------------------------------------- 1단계: dry-run

    private static void dryRun(Map<String, String> opt) throws Exception {
        parseCsv(resolveCsv(opt));
        System.out.println();
        System.out.println("[dry-run] API 도 DB 도 건드리지 않았습니다. 지오코딩: ConstructionZoneLoader geocode");
    }

    // --------------------------------------------------------- 2단계: geocode

    private static void geocodeOnly(Map<String, String> opt) throws Exception {
        List<Zone> zones = parseCsv(resolveCsv(opt));
        List<Located> located = geocodeAll(zones, readKakaoKey());
        report(located);
        System.out.println();
        System.out.println("[geocode] DB 는 건드리지 않았습니다. 적재: ConstructionZoneLoader load");
    }

    // ------------------------------------------------------------ 3단계: load

    private static void load(Map<String, String> opt) throws Exception {
        Properties app = readProperties();
        String region = opt.getOrDefault("region", app.getProperty("wheelway.region-id", DEFAULT_REGION));
        String url = app.getProperty("spring.datasource.url");
        String user = app.getProperty("spring.datasource.username");
        String password = app.getProperty("spring.datasource.password");

        if (url == null || user == null || password == null) {
            System.out.println("[중단] DB 접속 정보를 찾지 못했습니다. " + PROPS + " / " + LOCAL_PROPS + " 를 확인하세요.");
            return;
        }

        List<Zone> zones = parseCsv(resolveCsv(opt));
        List<Located> located = geocodeAll(zones, readKakaoKey());
        report(located);

        List<Located> insertable = located.stream().filter(l -> !l.failed()).toList();
        if (insertable.isEmpty()) {
            System.out.println("[중단] 적재할 것이 없습니다.");
            return;
        }

        System.out.println();
        System.out.println("=== 적재 === region=" + region);

        try (Connection con = DriverManager.getConnection(url, user, password)) {
            con.setAutoCommit(false);

            int existing = countRows(con, region);
            if (existing > 0) {
                if (!opt.containsKey("force")) {
                    System.out.println("[중단] region='" + region + "' 에 이미 " + existing + "행이 있습니다. "
                            + "덮어쓰려면 --force 를 주세요.");
                    return;
                }
                System.out.println("  [force] 기존 " + existing + "행 삭제");
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM CONSTRUCTION_ZONES WHERE REGION_ID = ?")) {
                    ps.setString(1, region);
                    ps.executeUpdate();
                }
            }

            String sql = "INSERT INTO CONSTRUCTION_ZONES "
                    + "(REGION_ID, NAME, ROAD_SEGMENT, ROAD_TYPE, LATITUDE, LONGITUDE, START_DATE, END_DATE) "
                    + "VALUES (?, ?, ?, '보도', ?, ?, ?, ?)";

            int inserted = 0;
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                for (Located l : insertable) {
                    ps.setString(1, region);
                    ps.setString(2, l.zone().name());
                    ps.setString(3, l.zone().roadSegment());
                    ps.setBigDecimal(4, coord(l.lat()));
                    ps.setBigDecimal(5, coord(l.lon()));
                    ps.setDate(6, Date.valueOf(l.zone().startDate()));
                    if (l.zone().endDate() == null) {
                        ps.setNull(7, java.sql.Types.DATE);
                    } else {
                        ps.setDate(7, Date.valueOf(l.zone().endDate()));
                    }
                    ps.addBatch();
                    inserted++;
                }
                ps.executeBatch();
            }
            con.commit();

            System.out.println("  CONSTRUCTION_ZONES 적재 " + inserted + "행");
        }

        System.out.println();
        System.out.println("[완료] 서버를 다시 띄우면 GraphHolder 가 이 좌표로 차단 Set 을 구성합니다.");
        System.out.println("       기동 로그의 '차단 엣지 N개' 를 확인하세요.");
    }

    private static int countRows(Connection con, String region) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM CONSTRUCTION_ZONES WHERE REGION_ID = ?")) {
            ps.setString(1, region);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ------------------------------------------------------------------ CSV

    /**
     * CSV 를 읽어 적재 대상만 남긴다. 제외한 것은 사유와 함께 전부 출력한다.
     * 조용히 버리면 나중에 "왜 67건 중 60건만 있지?" 를 다시 조사하게 된다.
     */
    private static List<Zone> parseCsv(Path csv) throws Exception {
        System.out.println("[csv] " + csv.toAbsolutePath());

        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalStateException("CSV 가 비어 있습니다.");
        }

        List<Zone> zones = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int badPeriod = 0;

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] cols = splitCsv(line);
            if (cols.length < 4) {
                continue;
            }

            String name = cols[0];
            String segment = cols[1];
            String period = cols[3];

            String[] ends = segment.split("~");
            String from = ends[0].trim();
            String to = (ends.length > 1) ? ends[1].trim() : from;

            // 시작과 끝이 다르면 구간이다. 좌표 한 쌍으로는 표현할 수 없다.
            if (!from.equals(to)) {
                skipped.add(segment + "   ← 구간(시작≠끝)");
                continue;
            }

            String query = stripJimok(from);
            // 번지가 없으면 도로나 동 전체를 가리키는 것이라 한 점으로 특정할 수 없다.
            if (!BUNJI.matcher(query).find()) {
                skipped.add(segment + "   ← 번지 없음");
                continue;
            }

            LocalDate[] dates = parsePeriod(period);
            if (dates == null) {
                skipped.add(segment + "   ← 공사기간 파싱 실패: " + period);
                badPeriod++;
                continue;
            }

            zones.add(new Zone(name, segment, ADDRESS_PREFIX + query, dates[0], dates[1]));
        }

        System.out.println("[csv] 전체 " + (lines.size() - 1) + "건 → 적재 대상 " + zones.size()
                + "건, 제외 " + skipped.size() + "건");
        if (!skipped.isEmpty()) {
            System.out.println();
            System.out.println("=== 제외 목록 ===");
            skipped.forEach(s -> System.out.println("  " + s));
        }
        if (badPeriod > 0) {
            System.out.println("[경고] 공사기간 파싱 실패 " + badPeriod + "건 — 크롤러 출력 형식이 바뀌었는지 확인하세요.");
        }
        return zones;
    }

    /**
     * 번지 뒤에 붙은 지목 한 글자를 뗀다. {@code 황학동 1104대 → 황학동 1104}
     *
     * <p>카카오 주소검색은 이 글자가 붙어 있으면 못 찾는다.
     */
    private static String stripJimok(String address) {
        Matcher m = JIMOK_TAIL.matcher(address.trim());
        return m.find() ? m.replaceAll("$1") : address.trim();
    }

    /** {@code 2026.07.28~2026.08.31} → [시작일, 종료일]. 종료일이 없으면 null. 실패 시 null 반환. */
    private static LocalDate[] parsePeriod(String period) {
        String[] parts = period.split("~");
        try {
            LocalDate start = LocalDate.parse(parts[0].trim(), DATE_FMT);
            LocalDate end = (parts.length > 1 && !parts[1].isBlank())
                    ? LocalDate.parse(parts[1].trim(), DATE_FMT) : null;
            return new LocalDate[]{start, end};
        } catch (Exception e) {
            return null;
        }
    }

    /** 큰따옴표로 감싼 CSV 한 줄을 자른다. 이 파일은 값 안에 콤마가 없어 이 정도로 충분하다. */
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

    // ------------------------------------------------------------- 지오코딩

    private static List<Located> geocodeAll(List<Zone> zones, String kakaoKey) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SEC))
                .build();

        System.out.println();
        System.out.println("=== 지오코딩 " + zones.size() + "건 (호출 간격 " + SLEEP_MS + "ms) ===");

        List<Located> out = new ArrayList<>(zones.size());
        for (int i = 0; i < zones.size(); i++) {
            Zone z = zones.get(i);
            Located l = geocode(client, kakaoKey, z);
            out.add(l);

            String mark = l.failed() ? "실패" : (l.outsideBbox() ? "범위밖" : "  ok");
            System.out.printf("  [%2d/%2d] %s  %s%n", i + 1, zones.size(), mark, z.query());

            if (i + 1 < zones.size()) {
                Thread.sleep(SLEEP_MS);
            }
        }
        return out;
    }

    /**
     * 카카오 주소검색. 응답의 {@code x} 가 경도, {@code y} 가 위도다(순서 주의).
     * 결과가 여러 개면 첫 번째를 쓴다 — 지번까지 준 주소라 대개 1건이다.
     */
    private static Located geocode(HttpClient client, String kakaoKey, Zone zone) throws Exception {
        String uri = KAKAO_ADDRESS_API + "?query="
                + URLEncoder.encode(zone.query(), StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder(URI.create(uri))
                .header("Authorization", "KakaoAK " + kakaoKey)
                .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            System.out.println("    [HTTP " + res.statusCode() + "] " + res.body());
            return new Located(zone, Double.NaN, Double.NaN, null);
        }

        JsonNode docs = MAPPER.readTree(res.body()).get("documents");
        if (docs == null || docs.isEmpty()) {
            return new Located(zone, Double.NaN, Double.NaN, null);
        }

        JsonNode first = docs.get(0);
        double lon = Double.parseDouble(first.get("x").asString());
        double lat = Double.parseDouble(first.get("y").asString());
        String matched = first.get("address_name").asString();

        return new Located(zone, lat, lon, matched);
    }

    // ------------------------------------------------------------------ 보고

    private static void report(List<Located> located) {
        List<Located> failed = located.stream().filter(Located::failed).toList();
        List<Located> outside = located.stream().filter(Located::outsideBbox).toList();

        System.out.println();
        System.out.println("=== 지오코딩 결과 ===");
        System.out.println("  성공 " + (located.size() - failed.size()) + " / 실패 " + failed.size());

        if (!failed.isEmpty()) {
            System.out.println();
            System.out.println("--- 실패 (주소 원문) ---");
            failed.forEach(l -> System.out.println("  " + l.zone().roadSegment()));
        }
        if (!outside.isEmpty()) {
            System.out.println();
            System.out.println("--- bbox 밖으로 나온 좌표 — 다른 구의 동명이 잡혔을 수 있습니다 ---");
            outside.forEach(l -> System.out.printf("  %s → %s (%.6f, %.6f)%n",
                    l.zone().query(), l.matchedAddress(), l.lat(), l.lon()));
        }
    }

    // ------------------------------------------------------------------ 유틸

    private static Path resolveCsv(Map<String, String> opt) {
        return Paths.get(opt.getOrDefault("csv", DEFAULT_CSV));
    }

    private static String readKakaoKey() {
        String key = readProperties().getProperty(KEY_KAKAO, "").trim();
        if (key.isEmpty()) {
            throw new IllegalStateException(
                    KEY_KAKAO + " 가 비어 있습니다. " + LOCAL_PROPS + " 에 REST API 키를 넣으세요.");
        }
        return key;
    }

    /** application.properties 를 읽고 credentials/api_keys.properties 로 덮는다. */
    private static Properties readProperties() {
        Properties props = new Properties();
        merge(props, PROPS);
        merge(props, LOCAL_PROPS);
        return props;
    }

    private static void merge(Properties into, String path) {
        Path file = Paths.get(path);
        if (!Files.exists(file)) {
            return;
        }
        try (var in = Files.newInputStream(file)) {
            Properties loaded = new Properties();
            loaded.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            into.putAll(loaded);
        } catch (Exception e) {
            System.out.println("[경고] " + path + " 를 읽지 못했습니다: " + e.getMessage());
        }
    }

    /** DECIMAL(10,7) 에 맞춘다. */
    private static BigDecimal coord(double v) {
        return BigDecimal.valueOf(v).setScale(7, RoundingMode.HALF_UP);
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

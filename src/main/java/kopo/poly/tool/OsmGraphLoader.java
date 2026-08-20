package kopo.poly.tool;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OSM Overpass JSON → NODES / EDGES 적재 도구.
 *
 * <p>휠체어 경로탐색 1단계(하드필터 + Dijkstra 검증)에 쓸 그래프를 DB에 채우는
 * <b>단독 실행 도구</b>다. {@link RoaddigCrawler} 와 마찬가지로 Spring Bean 이 아니고
 * main 메서드로만 실행한다.
 *
 * <h3>입력</h3>
 * Overpass API 결과 JSON({@code data/export(junggu_bbox).json}).
 * {@code elements[]} 안에 {@code type=node}(lat/lon 보유)와 {@code type=way}(nodes[] + tags)가 섞여 있다.
 * way 가 참조하는 node 는 전부 같은 파일 안에 좌표까지 들어있어야 한다(없으면 그 세그먼트를 버리고 집계에 남긴다).
 *
 * <h3>그래프 규칙 — 인계서(2026-08-05) 확정 사항</h3>
 * <ul>
 *   <li><b>엣지 = way 의 연속 node 쌍(세그먼트) 1개</b>. way 단위로 압축하지 않는다.
 *       중구 bbox 기준 세그먼트 26,908개 → 방향별 2개 생성으로 EDGES 53,816행이 되는 게 정상이다.</li>
 *   <li><b>방향별 2개 생성</b>. 나중에 붙일 경사도 가중치가 오르막/내리막에서 달라지기 때문이다.
 *       {@code oneway=yes} 는 보행자에게 의미가 없으므로 <b>무시</b>한다(차량 일방통행일 뿐이다).</li>
 *   <li><b>{@code highway=steps} 는 행을 만들되</b> {@code EXCLUDE_REASON='steps'} 로 표시한다.
 *       행 자체를 안 만드는 방식(A안)은 채택하지 않았다. 나중에 "계단 위치를 지도에 표시" 같은 기능을
 *       붙일 때 좌표·연결정보가 DB 에 있어야 하기 때문이다. 실제 제외는 서버 기동 시 인접리스트 구성 단계에서 한다.</li>
 *   <li><b>차도 계열(primary/residential/service 등)은 제외하지 않는다.</b> 과거 문서의
 *       {@code exclude_reason='roadway'} 는 잘못된 전제였고 폐기됐다. 하드필터가 거르는 건
 *       '보도에 해당하는 공사구역'이지 '차도라는 도로 종류'가 아니다.</li>
 *   <li><b>{@code underpass} 를 채운다(2026-08-12).</b> {@code layer<0} / {@code tunnel=yes} /
 *       {@code indoor=yes} 인 way 다. 지하상가·지하철 연결통로가 {@code highway=pedestrian} 으로
 *       그려져 있어 그냥 두면 <b>지상 보도와 똑같이 취급</b>된다 — 실측으로 중구 경로의
 *       37~92% 가 {@code 을지로지하상가} 같은 지하 구간이었다.
 *       진입이 계단·에스컬레이터인데 그 계단이 OSM 에 없으면 계단 하드필터도 우회한다.
 *       <br>엘리베이터가 있으면 실제로는 갈 수 있으나 그 데이터가 없다. 오차단을 감수하고 빼는 쪽이
 *       없는 길을 안내하는 것보다 낫다는 판단이다 — 엘리베이터 정보가 생기면 되돌릴 것.
 *       <br>{@code overpass}(육교)는 아직 채우지 않는다. {@code bridge=yes} 는 고가·교량과 섞여 있어
 *       그것만으로 육교를 가려낼 수 없다.</li>
 *   <li>서로 다른 way 가 같은 node id 를 공유하면 <b>실제로 연결된 교차로</b>다.
 *       좌표 근사 매칭이 아니라 node id 일치로만 판단한다(OSM 원본 형식을 쓰는 이유).</li>
 *   <li>bbox 가 중구 행정경계보다 넓어 용산·성동·종로 일부가 딸려오지만 <b>잘라내지 않는다</b>.
 *       그래프가 경계에서 끊기지 않게 하려는 의도된 결과다.</li>
 * </ul>
 *
 * <h3>이번 단계에 채우는 컬럼</h3>
 * NODES 는 {@code REGION_ID / OSM_NODE_ID / LATITUDE / LONGITUDE},
 * EDGES 는 {@code REGION_ID / FROM_NODE_ID / TO_NODE_ID / GEOMETRY_JSON / LENGTH_M /
 * OSM_WAY_ID / OSM_HIGHWAY / EXCLUDE_REASON(steps, underpass)}.
 * {@code ELEV_M / NODE_TYPE / SLOPE_PERCENT / ROAD_GRADE / WIDTH_M / VERIFIED_AT} 은 전부 NULL 이다
 * (DEM 미적용, 표준노드링크 미확보, 로드뷰 검증 전).
 *
 * <h3>GEOMETRY_JSON 형식</h3>
 * {@code [[위도,경도],[위도,경도]]} — 위도 먼저다(카카오맵 {@code LatLng} 순서와 같다).
 * 세그먼트 단위 엣지라 지금은 항상 2점이지만, 나중에 way 단위로 압축하게 되면 중간점이 늘어나는 자리다.
 *
 * <h3>실행 방법</h3>
 * <pre>
 *   // 파싱·검증만. DB 접속 안 함. 비밀번호 없이 그래프가 제대로 만들어지는지 먼저 확인할 때 쓴다.
 *   OsmGraphLoader dry-run
 *
 *   // 실제 적재 (비밀번호는 환경변수 WHEELWAY_DB_PASSWORD 권장)
 *   OsmGraphLoader load
 *   OsmGraphLoader load --password=...            // 환경변수 대신 인자로 줄 때
 *   OsmGraphLoader load --force                   // 같은 region 데이터가 이미 있으면 지우고 다시 넣기
 * </pre>
 *
 * <p><b>주의</b>: {@code --password} 로 주면 IntelliJ Run Configuration 에 평문으로 남는다.
 * 환경변수 쪽을 권한다.
 *
 * <h3>적재 순서</h3>
 * NODES 를 먼저 넣고 {@code (REGION_ID, OSM_NODE_ID) → NODES.ID} 매핑을 <b>DB 에서 다시 읽어와</b>
 * EDGES 의 FK 를 채운다. ID 를 우리가 임의로 지정하지 않는 이유는 AUTO_INCREMENT 와 충돌하지 않게 하고
 * 다른 region 을 나중에 추가로 넣을 수 있게 하기 위해서다.
 * 대량 적재 동안에는 {@code FOREIGN_KEY_CHECKS=0} 으로 잠깐 꺼둔다(FK 체크로 인한 속도 저하 회피).
 */
public final class OsmGraphLoader {

    /** 기본 입력 파일. IntelliJ 에서 인자 없이 Run 하면 이 경로를 본다. */
    private static final String DEFAULT_JSON = "data/export(junggu_bbox).json";

    /** 지역 서브그래프 키. 2026-08-05 확정값. */
    private static final String DEFAULT_REGION = "seoul-junggu";

    /** 접속 정보를 가져올 곳. 여기 값이 있으면 아래 기본값 대신 그것을 쓴다. */
    private static final String DEFAULT_PROPS = "src/main/resources/application.properties";

    /**
     * 비밀값 파일. {@code .gitignore} 에 있어 커밋되지 않는다.
     * 위 파일보다 나중에 읽어서 덮어쓴다 — application.properties 의
     * {@code spring.config.import} 가 하는 일을 Spring 없이 직접 하는 것이다.
     */
    private static final String LOCAL_PROPS = "credentials/api_keys.properties";

    private static final String DEFAULT_JDBC_URL = "jdbc:mariadb://192.168.89.129:3306/wheelway";
    private static final String DEFAULT_DB_USER = "wheel";

    /** 비밀번호를 인자 대신 여기서 읽는다. IntelliJ Run Configuration 의 Environment variables 에 넣으면 된다. */
    private static final String ENV_DB_PASSWORD = "WHEELWAY_DB_PASSWORD";

    /** 하드필터 대상 — 계단. EDGES 에 넣되 이 값으로 표시해두고 인접리스트 구성 때 뺀다. */
    private static final String HIGHWAY_STEPS = "steps";
    private static final String EXCLUDE_REASON_STEPS = "steps";

    /**
     * 하드필터 대상 — 지하·실내 통로.
     *
     * <p>{@code 을지로지하상가} 처럼 지하상가·지하철 연결통로가 {@code highway=pedestrian} 으로
     * 그려져 있다. {@code layer}/{@code tunnel} 을 안 보면 <b>지상 보도와 똑같이 취급</b>되어
     * 도로 밑을 곧게 가로지르는 경로가 나온다. 실제로 중구 경로의 37~92% 가 이런 구간이었다.
     *
     * <p>휠체어 기준으로는 더 나쁘다 — 진입이 계단·에스컬레이터인 경우가 많은데 그 계단이
     * OSM 에 없으면 <b>그냥 걸어 들어가는 것으로 계산된다.</b> 계단 하드필터를 우회하는 셈이다.
     *
     * <p>엘리베이터가 있으면 실제로는 갈 수 있지만 그 데이터가 없다. 없는 길을 안내하느니
     * 돌아가는 쪽이 낫다는 판단이며, 계단·공사에서 취해온 방침과 같다.
     */
    private static final String EXCLUDE_REASON_UNDERPASS = "underpass";

    /** JDBC 배치 크기. 5만행대라 이 정도면 메모리·왕복 둘 다 무난하다. */
    private static final int BATCH_SIZE = 1_000;

    /**
     * 지구 평균 반지름(m). IUGG mean radius.
     * 중구 bbox 규모(수 km)에서 하버사인 오차는 cm 단위라 경로 비교에 영향이 없다.
     */
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    /**
     * 이 길이(m)를 넘는 세그먼트는 중간에 노드를 넣어 쪼갠다. {@code --split=0} 이면 쪼개지 않는다.
     *
     * <p><b>왜 필요한가</b>: 공사·제보 좌표는 반경 {@code wheelway.block-radius-m}(20m) 안의 엣지를 막는데,
     * 엣지는 <b>길이와 무관하게 통째로</b> 막힌다. Dijkstra 가 엣지의 절반만 지나갈 수 없기 때문이다.
     * 원본 세그먼트는 중앙값 11m 로 대부분 짧지만 최대 886m 짜리가 있어서,
     * CCTV 설치 공사 하나 때문에 230m 짜리 길이 통째로 통행 불가가 되는 일이 실제로 확인됐다.
     *
     * <p>25m 로 잡은 이유는 차단 반경 20m 와 같은 규모여서 과차단이 반경 수준으로 억제되기 때문이다.
     * 세그먼트가 26,908 → 약 36,500(+36%) 으로 늘지만 Dijkstra 는 수십 ms 규모라 여유가 있다.
     *
     * <p>덤으로 스냅 정확도도 좋아진다. 긴 길 한가운데에 붙을 노드가 생기기 때문이다.
     */
    private static final double DEFAULT_SPLIT_M = 50.0;

    /**
     * 공사 주변에서 쓰는 분할 길이(m). {@code 0} 이면 주변도 위 값으로 똑같이 쪼갠다.
     *
     * <p>정밀한 차단이 필요한 곳은 <b>공사 주변 7% 뿐</b>이다. 나머지는 그냥 길이라
     * 25m 로 쪼개든 50m 로 쪼개든 경로 결과가 같다. 필요한 데만 잘게 쓰고 아닌 데서 아끼면
     * 전역 25m 로 쪼갤 때보다 노드가 오히려 줄면서 공사 주변 정밀도는 5배가 된다.
     *
     * <pre>
     *   전역 25m         : 노드 33,360 / 공사 주변 정밀도 25m
     *   주변 5m + 밖 50m : 노드 32,702 / 공사 주변 정밀도  5m
     * </pre>
     *
     * <p>차단 도달 거리는 {@code 반경 + 엣지 길이} 이므로, 반경 8m 인 공사라면
     * 45m(25m 엣지)에서 13m(5m 엣지)로 줄어든다.
     */
    private static final double DEFAULT_SPLIT_NEAR_M = 5.0;

    /**
     * 공사 좌표에서 이 거리 안의 세그먼트를 '주변'으로 본다.
     *
     * <p><b>주의</b>: 화면에서 공사 좌표를 이 거리 이상 옮기면 잘게 쪼갠 영역을 벗어난다.
     * 좌표 수정을 마친 뒤 {@code load --force} 를 한 번 더 돌리면 새 위치 기준으로 다시 잡힌다.
     */
    private static final double DEFAULT_SPLIT_NEAR_RADIUS_M = 60.0;

    /**
     * 분할로 새로 만든 노드의 {@code NODE_TYPE}.
     * {@code OSM_NODE_ID} 가 NULL 인 것(= 수동 추가 노드)과 함께 판별 근거가 된다.
     */
    private static final String NODE_TYPE_SPLIT = "split";

    /** 경고가 콘솔을 덮지 않도록 원문 예시는 앞쪽 몇 건만 찍는다. */
    private static final int MAX_WARN_SAMPLES = 10;

    /** Spring Boot 4 가 얹어주는 Jackson 3 (tools.jackson). 크롤러와 같은 것을 쓴다. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private OsmGraphLoader() {
    }

    // ------------------------------------------------------------------ 자료형

    /**
     * 그래프 노드 1개. 좌표만 쓰고 태그는 이번 단계에서 보지 않는다.
     *
     * @param osmId OSM node id. <b>{@code null} 이면 긴 세그먼트를 쪼개면서 새로 만든 노드</b>다
     *              (DDL 의 "NULL이면 수동 추가 노드"가 이 경우다).
     */
    private record OsmNode(Long osmId, double lat, double lon) {

        boolean split() {
            return osmId == null;
        }

        /**
         * 적재 후 DB 의 ID 를 되찾을 때 쓰는 키.
         * 분할 노드는 OSM id 가 없어서 좌표로 찾는다. 소수점 7자리(약 1cm)면 서로 겹치지 않는다.
         */
        String coordKey() {
            return coord(lat).toPlainString() + "," + coord(lon).toPlainString();
        }
    }

    /**
     * 방향 없는 세그먼트 1개 = way 의 연속 node 쌍.
     * EDGES 행은 여기서 방향별로 2개가 나온다.
     */
    private record Segment(OsmNode from, OsmNode to, double lengthM, long wayId, String highway,
                           boolean underground) {

        /**
         * 하드필터 표시값. 계단이 우선이다 — 지하로 내려가는 계단은 둘 다 해당하는데,
         * 통계에서 계단으로 세는 편이 원인을 읽기 쉽다.
         */
        String excludeReason() {
            if (HIGHWAY_STEPS.equals(highway)) {
                return EXCLUDE_REASON_STEPS;
            }
            return underground ? EXCLUDE_REASON_UNDERPASS : null;
        }

        String geometryForward() {
            return geometry(from, to);
        }

        /** 반대 방향 엣지는 좌표열도 뒤집어야 폴리라인이 진행 방향과 맞는다. */
        String geometryReversed() {
            return geometry(to, from);
        }

        /** {@code [[위도,경도],[위도,경도]]}. 위도 먼저 — 카카오맵 LatLng 순서와 같다. */
        private static String geometry(OsmNode a, OsmNode b) {
            return "[[" + coord(a.lat()).toPlainString() + "," + coord(a.lon()).toPlainString() + "],["
                    + coord(b.lat()).toPlainString() + "," + coord(b.lon()).toPlainString() + "]]";
        }
    }

    /**
     * 지하·실내 통로인가. {@code layer} 가 음수이거나 {@code tunnel}/{@code indoor} 가 붙은 way 다.
     *
     * <p>{@code tunnel=building_passage} 는 건물을 관통하는 통로로, 지상이지만 사유지 안이라
     * 항상 다닐 수 있다고 볼 수 없어 같이 뺀다.
     *
     * <p>{@code layer} 는 값이 {@code "-1"} 같은 문자열이고 가끔 이상한 값이 들어온다.
     * 숫자가 아니면 지상으로 본다 — 못 읽는 값 때문에 멀쩡한 길을 빼는 편이 더 나쁘다.
     */
    private static boolean isUnderground(JsonNode tags) {
        if (tags == null) {
            return false;
        }
        String tunnel = text(tags, "tunnel");
        if ("yes".equals(tunnel) || "building_passage".equals(tunnel)) {
            return true;
        }
        if ("yes".equals(text(tags, "indoor"))) {
            return true;
        }
        String layer = text(tags, "layer");
        if (layer != null && !layer.isBlank()) {
            try {
                return Integer.parseInt(layer.trim()) < 0;
            } catch (NumberFormatException ignore) {
                return false;
            }
        }
        return false;
    }

    /**
     * 세그먼트를 얼마나 잘게 쪼갤지 정하는 규칙.
     *
     * <p>공사 좌표 근처는 {@code nearM}, 나머지는 {@code farM} 으로 쪼갠다.
     * 엣지는 길이와 무관하게 통째로 막히므로, 정밀한 차단이 필요한 곳만 잘게 만드는 것이다.
     *
     * @param points 공사 좌표 {@code [위도, 경도]}. 비어 있으면 전부 {@code farM} 을 쓴다
     */
    private record SplitPolicy(double farM, double nearM, double nearRadiusM, List<double[]> points) {

        /** 이 세그먼트에 적용할 분할 길이. */
        double stepFor(OsmNode a, OsmNode b) {
            if (nearM <= 0 || points.isEmpty()) {
                return farM;
            }
            for (double[] p : points) {
                if (outsideBox(p, a, b)) {
                    continue;
                }
                if (pointToSegmentM(p[0], p[1], a.lat(), a.lon(), b.lat(), b.lon()) <= nearRadiusM) {
                    return nearM;
                }
            }
            return farM;
        }

        /**
         * 선분 주변 상자 밖이면 거리 계산을 건너뛴다.
         * 세그먼트 2만7천 개 × 공사 60건이라 이 사전 거르기가 없으면 눈에 띄게 느려진다.
         * 여유값 0.003도(약 330m)는 nearRadiusM 보다 넉넉해서 걸러야 할 것을 놓치지 않는다.
         */
        private boolean outsideBox(double[] p, OsmNode a, OsmNode b) {
            double m = 0.003;
            return p[0] < Math.min(a.lat(), b.lat()) - m || p[0] > Math.max(a.lat(), b.lat()) + m
                    || p[1] < Math.min(a.lon(), b.lon()) - m || p[1] > Math.max(a.lon(), b.lon()) + m;
        }
    }

    /**
     * 파싱 결과 묶음. dry-run 과 load 가 같은 것을 본다.
     *
     * @param nodes    적재할 노드 전체. 실제 OSM 노드와 분할 노드가 섞여 있다
     * @param splitAdded 분할로 새로 만든 노드 수
     */
    private record Graph(List<OsmNode> nodes, List<Segment> segments,
                         Map<String, Integer> highwayDist, int splitAdded, SplitPolicy policy) {
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        // 콘솔 한글 깨짐 방지. IntelliJ 콘솔은 UTF-8 이 기본이라 이걸로 맞는다.
        // (윈도우 cmd 에서 직접 돌릴 때는 chcp 65001 필요)
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        // 인자 없이 Run 하면 dry-run. 실수로 DB 를 건드리는 쪽이 기본값이 되지 않게 한다.
        String mode = (args.length > 0 && !args[0].startsWith("--")) ? args[0] : "dry-run";
        Map<String, String> opt = parseOptions(args);

        switch (mode) {
            case "dry-run" -> dryRun(opt);
            case "load" -> load(opt);
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("사용법: OsmGraphLoader [dry-run|load] [옵션]");
        System.out.println("  dry-run  (기본) JSON 파싱 + 그래프 검증만. DB 접속 안 함");
        System.out.println("  load           NODES / EDGES 적재");
        System.out.println();
        System.out.println("  공통 옵션:");
        System.out.println("    --json=" + DEFAULT_JSON);
        System.out.println("    --region=" + DEFAULT_REGION);
        System.out.println("    --split=" + DEFAULT_SPLIT_M
                + "             공사에서 먼 곳의 분할 길이. 0 이면 쪼개지 않음");
        System.out.println("    --split-near=" + DEFAULT_SPLIT_NEAR_M
                + "         공사 주변의 분할 길이. 0 이면 주변도 위 값으로 동일");
        System.out.println("    --split-near-radius=" + DEFAULT_SPLIT_NEAR_RADIUS_M
                + " 공사 좌표에서 이 거리 안을 '주변'으로 본다");
        System.out.println();
        System.out.println("  load 옵션: (접속 정보는 기본적으로 " + DEFAULT_PROPS + " 에서 읽는다)");
        System.out.println("    --props=" + DEFAULT_PROPS);
        System.out.println("    --url=...        properties 값을 덮어쓸 때만");
        System.out.println("    --user=...");
        System.out.println("    --password=...   properties 에 없을 때. 환경변수 " + ENV_DB_PASSWORD + " 도 가능");
        System.out.println("    --force          같은 region 의 기존 NODES/EDGES 를 지우고 다시 적재");
    }

    // --------------------------------------------------------- 1단계: dry-run

    /**
     * DB 없이 파싱 결과만 검증한다.
     * 인계서에 적힌 기대 수치(way 4,738 / node 23,769 / 세그먼트 26,908 / 엣지 53,816)와
     * 실제 파싱 결과가 맞는지 여기서 먼저 본다.
     */
    private static void dryRun(Map<String, String> opt) throws Exception {
        // 공사 좌표는 읽기만 한다. 못 읽으면 전 구간을 같은 간격으로 쪼갠 결과를 보여준다.
        Graph g = parse(resolveJsonPath(opt), resolveSplitPolicy(opt));
        report(g);

        System.out.println();
        System.out.println("=== 연결성 (steps 제외 후) ===");
        reportConnectivity(g);

        System.out.println();
        System.out.println("[dry-run] DB 는 건드리지 않았습니다. 적재하려면: OsmGraphLoader load");
    }

    // ------------------------------------------------------------ 2단계: load

    private static void load(Map<String, String> opt) throws Exception {
        // 접속 정보는 application.properties 한 곳에만 둔다. 두 군데 적어두면 언젠가 어긋난다.
        Properties app = readAppProperties(opt.getOrDefault("props", DEFAULT_PROPS));

        String region = opt.getOrDefault("region",
                app.getProperty("wheelway.region-id", DEFAULT_REGION));
        String url = opt.getOrDefault("url",
                app.getProperty("spring.datasource.url", DEFAULT_JDBC_URL));
        String user = opt.getOrDefault("user",
                app.getProperty("spring.datasource.username", DEFAULT_DB_USER));

        String password = opt.get("password");
        if (password == null) {
            password = System.getenv(ENV_DB_PASSWORD);
        }
        if (password == null) {
            password = app.getProperty("spring.datasource.password");
        }
        if (password == null) {
            System.out.println("[중단] 비밀번호를 찾지 못했습니다.");
            System.out.println("       " + DEFAULT_PROPS + " 의 spring.datasource.password 또는");
            System.out.println("       환경변수 " + ENV_DB_PASSWORD + " 또는 --password= 중 하나가 필요합니다.");
            return;
        }
        boolean force = opt.containsKey("force");

        Graph g = parse(resolveJsonPath(opt), resolveSplitPolicy(opt));
        report(g);

        // 배치 INSERT 를 한 번의 왕복으로 묶으려면 이 옵션이 필요하다. 없으면 5만행이 수십 배 느려진다.
        String jdbcUrl = url.contains("rewriteBatchedStatements")
                ? url
                : url + (url.contains("?") ? "&" : "?") + "rewriteBatchedStatements=true";

        System.out.println();
        System.out.println("=== 적재 ===");
        System.out.println("  url    = " + url);
        System.out.println("  user   = " + user);
        System.out.println("  region = " + region);

        long begin = System.currentTimeMillis();
        try (Connection con = DriverManager.getConnection(jdbcUrl, user, password)) {
            con.setAutoCommit(false);

            prepareRegion(con, region, force);

            try (Statement st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS=0");
            }

            int nodeRows = insertNodes(con, region, g.nodes());
            con.commit();
            System.out.println("  NODES 적재 " + nodeRows + "행");

            List<Map<?, Long>> idMaps = readNodeIdMaps(con, region);
            int recovered = idMaps.get(0).size() + idMaps.get(1).size();
            System.out.println("  NODES.ID 매핑 " + recovered + "건 회수"
                    + " (OSM " + idMaps.get(0).size() + " + 분할 " + idMaps.get(1).size() + ")");
            if (recovered != g.nodes().size()) {
                throw new IllegalStateException(
                        "NODES 적재 수와 회수한 매핑 수가 다릅니다. 적재=" + g.nodes().size() + " 회수=" + recovered);
            }

            int edgeRows = insertEdges(con, region, g.segments(), idMaps);
            con.commit();
            System.out.println("  EDGES 적재 " + edgeRows + "행");

            try (Statement st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS=1");
            }
            con.commit();
        }

        System.out.printf("[완료] %.1f초%n", (System.currentTimeMillis() - begin) / 1000.0);
        System.out.println("       확인: SELECT COUNT(*) FROM EDGES WHERE REGION_ID='" + region + "';");
        System.out.println("             SELECT COUNT(*) FROM EDGES WHERE REGION_ID='" + region
                + "' AND EXCLUDE_REASON='steps';");
    }

    /**
     * 같은 region 데이터가 이미 있으면 중단한다. {@code --force} 면 지우고 진행한다.
     * EDGES 가 NODES 를 FK 로 잡고 있으니 EDGES 를 먼저 지운다.
     */
    private static void prepareRegion(Connection con, String region, boolean force) throws Exception {
        int nodes = countRows(con, "NODES", region);
        int edges = countRows(con, "EDGES", region);
        if (nodes == 0 && edges == 0) {
            return;
        }

        if (!force) {
            throw new IllegalStateException(
                    "region='" + region + "' 데이터가 이미 있습니다 (NODES " + nodes + "행, EDGES " + edges + "행). "
                            + "덮어쓰려면 --force 를 주세요.");
        }

        System.out.println("  [force] 기존 데이터 삭제 — NODES " + nodes + "행, EDGES " + edges + "행");
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM EDGES WHERE REGION_ID = ?")) {
            ps.setString(1, region);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM NODES WHERE REGION_ID = ?")) {
            ps.setString(1, region);
            ps.executeUpdate();
        }
        con.commit();
    }

    private static int countRows(Connection con, String table, String region) throws Exception {
        // table 은 이 클래스 안의 상수 문자열만 들어온다(외부 입력 아님).
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE REGION_ID = ?")) {
            ps.setString(1, region);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * NODES 적재. ID 는 AUTO_INCREMENT 에 맡기고 {@code ELEV_M} 은 NULL 로 둔다(DEM 미적용).
     *
     * <p>분할 노드는 {@code OSM_NODE_ID} 를 NULL 로, {@code NODE_TYPE} 을 {@code 'split'} 로 넣는다.
     * DDL 의 "NULL이면 수동 추가 노드"가 이 경우다. MariaDB 의 UNIQUE 인덱스는 NULL 을 여럿 허용하므로
     * {@code UQ_NODES_REGION_OSM} 에 걸리지 않는다.
     */
    private static int insertNodes(Connection con, String region, List<OsmNode> nodes) throws Exception {
        String sql = "INSERT INTO NODES (REGION_ID, OSM_NODE_ID, LATITUDE, LONGITUDE, NODE_TYPE) "
                + "VALUES (?, ?, ?, ?, ?)";
        int total = 0;
        int pending = 0;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (OsmNode n : nodes) {
                ps.setString(1, region);
                if (n.split()) {
                    ps.setNull(2, java.sql.Types.BIGINT);
                    ps.setString(5, NODE_TYPE_SPLIT);
                } else {
                    ps.setLong(2, n.osmId());
                    ps.setNull(5, java.sql.Types.VARCHAR);
                }
                ps.setBigDecimal(3, coord(n.lat()));
                ps.setBigDecimal(4, coord(n.lon()));
                ps.addBatch();
                if (++pending >= BATCH_SIZE) {
                    total += sum(ps.executeBatch());
                    pending = 0;
                }
            }
            if (pending > 0) {
                total += sum(ps.executeBatch());
            }
        }
        return total;
    }

    /**
     * 적재된 NODES 에서 {@code → NODES.ID} 매핑을 되찾는다. EDGES 의 FROM/TO 를 채우는 데 쓴다.
     *
     * <p>OSM 노드는 {@code OSM_NODE_ID} 로, 분할 노드는 좌표로 찾는다.
     * 분할 노드에는 되찾을 자연키가 없어서 좌표(소수점 7자리, 약 1cm)를 키로 쓴다.
     * OSM 노드까지 좌표로 찾지 않는 이유는 <b>좌표가 완전히 같은 서로 다른 OSM 노드가 실제로 존재</b>하기
     * 때문이다(원본에 길이 0 세그먼트가 100개 있다).
     *
     * @return {@code [osmId → ID, 좌표키 → ID]}
     */
    private static List<Map<?, Long>> readNodeIdMaps(Connection con, String region) throws Exception {
        Map<Long, Long> byOsmId = new HashMap<>(32_768);
        Map<String, Long> byCoord = new HashMap<>(16_384);

        try (PreparedStatement ps = con.prepareStatement(
                "SELECT ID, OSM_NODE_ID, LATITUDE, LONGITUDE FROM NODES WHERE REGION_ID = ?")) {
            ps.setString(1, region);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    long osmId = rs.getLong(2);
                    if (rs.wasNull()) {
                        byCoord.put(rs.getBigDecimal(3).toPlainString() + ","
                                + rs.getBigDecimal(4).toPlainString(), id);
                    } else {
                        byOsmId.put(osmId, id);
                    }
                }
            }
        }
        return List.of(byOsmId, byCoord);
    }

    /** 세그먼트 1개당 방향별로 2행. GEOMETRY_JSON 도 방향에 맞춰 뒤집는다. */
    @SuppressWarnings("unchecked")
    private static int insertEdges(Connection con, String region, List<Segment> segments,
                                   List<Map<?, Long>> idMaps) throws Exception {

        Map<Long, Long> byOsmId = (Map<Long, Long>) idMaps.get(0);
        Map<String, Long> byCoord = (Map<String, Long>) idMaps.get(1);

        String sql = "INSERT INTO EDGES "
                + "(REGION_ID, FROM_NODE_ID, TO_NODE_ID, GEOMETRY_JSON, LENGTH_M, "
                + " OSM_WAY_ID, OSM_HIGHWAY, EXCLUDE_REASON) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        int total = 0;
        int pending = 0;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (Segment s : segments) {
                Long from = nodeId(s.from(), byOsmId, byCoord);
                Long to = nodeId(s.to(), byOsmId, byCoord);
                if (from == null || to == null) {
                    throw new IllegalStateException(
                            "NODES 매핑 누락: " + s.from().coordKey() + " → " + s.to().coordKey());
                }

                pending += bindEdge(ps, region, from, to, s, false);
                pending += bindEdge(ps, region, to, from, s, true);

                if (pending >= BATCH_SIZE) {
                    total += sum(ps.executeBatch());
                    pending = 0;
                }
            }
            if (pending > 0) {
                total += sum(ps.executeBatch());
            }
        }
        return total;
    }

    /** 노드가 OSM 것이면 osmId 로, 분할 노드면 좌표로 DB ID 를 찾는다. */
    private static Long nodeId(OsmNode n, Map<Long, Long> byOsmId, Map<String, Long> byCoord) {
        return n.split() ? byCoord.get(n.coordKey()) : byOsmId.get(n.osmId());
    }

    private static int bindEdge(PreparedStatement ps, String region, long fromId, long toId,
                                Segment s, boolean reversed) throws Exception {
        ps.setString(1, region);
        ps.setLong(2, fromId);
        ps.setLong(3, toId);
        ps.setString(4, reversed ? s.geometryReversed() : s.geometryForward());
        ps.setBigDecimal(5, meters(s.lengthM()));
        ps.setLong(6, s.wayId());
        ps.setString(7, s.highway());
        ps.setString(8, s.excludeReason());
        ps.addBatch();
        return 1;
    }

    // ------------------------------------------------------------------ 파싱

    /**
     * Overpass JSON 을 읽어 노드 좌표표와 세그먼트 목록을 만든다.
     *
     * <p>버리는 경우는 두 가지뿐이고 둘 다 집계에 남긴다.
     * <ul>
     *   <li>way 가 참조하는 node 의 좌표가 파일에 없는 경우 — 그 세그먼트만 버린다</li>
     *   <li>같은 node 가 연속으로 나와 길이 0 이 되는 경우 — 자기 자신으로 가는 엣지라 의미가 없다</li>
     * </ul>
     */
    private static Graph parse(Path json, SplitPolicy policy) throws Exception {
        System.out.println("[parse] " + json.toAbsolutePath());
        if (policy.points().isEmpty() || policy.nearM() <= 0) {
            System.out.println("[parse] 분할 기준 " + (policy.farM() > 0 ? policy.farM() + "m" : "없음") + " (전 구간 동일)");
        } else {
            System.out.printf("[parse] 분할 기준 — 공사 %d곳 반경 %.0fm 안 %.0fm / 밖 %.0fm%n",
                    policy.points().size(), policy.nearRadiusM(), policy.nearM(), policy.farM());
        }
        long begin = System.currentTimeMillis();

        JsonNode root;
        try (var in = Files.newInputStream(json)) {
            root = MAPPER.readTree(in);
        }
        JsonNode elements = root.get("elements");
        if (elements == null || !elements.isArray()) {
            throw new IllegalStateException("elements 배열이 없습니다. Overpass JSON 이 맞는지 확인하세요.");
        }

        Map<Long, OsmNode> allNodes = new HashMap<>(32_768);
        List<JsonNode> ways = new ArrayList<>(8_192);

        for (JsonNode e : elements) {
            String type = text(e, "type");
            if ("node".equals(type)) {
                JsonNode lat = e.get("lat");
                JsonNode lon = e.get("lon");
                if (lat == null || lon == null) {
                    continue;
                }
                long id = e.get("id").asLong();
                allNodes.put(id, new OsmNode(id, lat.asDouble(), lon.asDouble()));
            } else if ("way".equals(type)) {
                ways.add(e);
            }
        }

        Map<String, Integer> highwayDist = new LinkedHashMap<>();
        List<Segment> segments = new ArrayList<>(40_960);
        Set<Long> usedNodes = new HashSet<>(32_768);

        /*
         * 분할 노드는 좌표를 키로 모은다. 좌표가 완전히 같은 분할점이 실제로 생기는데
         * (겹친 way 에서 같은 지점이 두 번 찍히는 경우, 중구 기준 19쌍),
         * 그대로 두면 DB 에 같은 자리 노드가 둘 생기고 좌표로 ID 를 되찾을 때 하나가 묻힌다.
         * 어차피 1cm 안쪽이면 같은 지점이므로 하나로 합치는 것이 맞다.
         */
        Map<String, OsmNode> splitPool = new LinkedHashMap<>(16_384);
        int splitMerged = 0;
        int nearSegments = 0;

        int missingCoord = 0;
        int selfLoop = 0;
        int noHighway = 0;
        int undergroundWays = 0;
        List<String> warnSamples = new ArrayList<>();

        for (JsonNode w : ways) {
            long wayId = w.get("id").asLong();
            JsonNode tags = w.get("tags");
            String highway = (tags == null) ? null : text(tags, "highway");
            if (highway == null || highway.isEmpty()) {
                // highway 태그가 없는 way 는 도로가 아니다(건물 윤곽 등). 그래프에 넣지 않는다.
                noHighway++;
                continue;
            }
            highwayDist.merge(highway, 1, Integer::sum);
            boolean under = isUnderground(tags);
            if (under) {
                undergroundWays++;
            }

            JsonNode refs = w.get("nodes");
            if (refs == null || refs.size() < 2) {
                continue;
            }

            for (int i = 0; i + 1 < refs.size(); i++) {
                long a = refs.get(i).asLong();
                long b = refs.get(i + 1).asLong();

                if (a == b) {
                    selfLoop++;
                    continue;
                }
                OsmNode na = allNodes.get(a);
                OsmNode nb = allNodes.get(b);
                if (na == null || nb == null) {
                    missingCoord++;
                    if (warnSamples.size() < MAX_WARN_SAMPLES) {
                        warnSamples.add("way " + wayId + " 의 node " + (na == null ? a : b) + " 좌표 없음");
                    }
                    continue;
                }

                usedNodes.add(a);
                usedNodes.add(b);

                // 긴 세그먼트는 중간에 노드를 넣어 쪼갠다. 그래야 공사 하나가 긴 길을 통째로 막지 않는다.
                // 공사 주변은 더 잘게 쪼갠다 — 정밀한 차단이 필요한 곳이 거기뿐이라서다.
                double step = policy.stepFor(na, nb);
                if (step == policy.nearM() && policy.nearM() < policy.farM()) {
                    nearSegments++;
                }

                OsmNode prev = na;
                for (OsmNode mid : splitPoints(na, nb, step)) {
                    OsmNode node = splitPool.putIfAbsent(mid.coordKey(), mid);
                    if (node == null) {
                        node = mid;
                    } else {
                        splitMerged++;
                    }
                    segments.add(new Segment(prev, node, haversineM(prev, node), wayId, highway, under));
                    prev = node;
                }
                segments.add(new Segment(prev, nb, haversineM(prev, nb), wayId, highway, under));
            }
        }

        // 세그먼트에 한 번도 안 쓰인 노드는 적재하지 않는다. FK 대상이 아니고 스냅 후보로도 쓸모가 없다.
        List<OsmNode> nodes = new ArrayList<>(usedNodes.size() + splitPool.size());
        for (Long id : usedNodes) {
            nodes.add(allNodes.get(id));
        }
        nodes.addAll(splitPool.values());

        if (nearSegments > 0) {
            System.out.printf("[parse] 공사 주변으로 판정해 잘게 쪼갠 원본 세그먼트 %d개 (전체의 %.1f%%)%n",
                    nearSegments, 100.0 * nearSegments / 26_908);
        }
        if (splitMerged > 0) {
            System.out.println("[parse] 좌표가 같아 하나로 합친 분할 노드 " + splitMerged + "건");
        }

        System.out.printf("[parse] %.1f초 — node %d개(그래프에 쓰이는 것 %d개), way %d개%n",
                (System.currentTimeMillis() - begin) / 1000.0, allNodes.size(), nodes.size(), ways.size());
        if (noHighway > 0) {
            System.out.println("[parse] highway 태그 없는 way " + noHighway + "개 제외");
        }
        if (selfLoop > 0) {
            System.out.println("[parse] 같은 node 가 연속된 세그먼트 " + selfLoop + "개 제외");
        }
        if (missingCoord > 0) {
            System.out.println("[경고] 좌표 없는 node 참조로 버린 세그먼트 " + missingCoord + "개");
            warnSamples.forEach(s -> System.out.println("       " + s));
        }

        return new Graph(nodes, segments, highwayDist, splitPool.size(), policy);
    }

    // ------------------------------------------------------------------ 보고

    private static void report(Graph g) {
        // 사유별로 따로 센다. 합쳐 놓으면 "왜 이만큼 빠졌는지" 를 못 읽는다.
        int steps = 0, underpass = 0;
        double stepsM = 0, underpassM = 0, longest = 0;
        for (Segment s : g.segments()) {
            String why = s.excludeReason();
            if (EXCLUDE_REASON_STEPS.equals(why)) {
                steps++;
                stepsM += s.lengthM();
            } else if (EXCLUDE_REASON_UNDERPASS.equals(why)) {
                underpass++;
                underpassM += s.lengthM();
            }
            longest = Math.max(longest, s.lengthM());
        }
        int excluded = steps + underpass;

        System.out.println();
        System.out.println("=== 그래프 요약 ===");
        System.out.println("  NODES 적재 대상        " + g.nodes().size()
                + " (OSM " + (g.nodes().size() - g.splitAdded()) + " + 분할 " + g.splitAdded() + ")");
        System.out.println("  세그먼트(방향 없음)     " + g.segments().size());
        System.out.println("  EDGES 적재 대상(×2)    " + (g.segments().size() * 2));
        System.out.printf("  └ exclude_reason=steps     %6d (세그먼트 %5d, %.0fm)%n",
                steps * 2, steps, stepsM);
        System.out.printf("  └ exclude_reason=underpass %6d (세그먼트 %5d, %.0fm)%n",
                underpass * 2, underpass, underpassM);
        System.out.println("  └ 탐색에 쓰이는 엣지        " + ((g.segments().size() - excluded) * 2));
        boolean localized = g.policy().nearM() > 0 && !g.policy().points().isEmpty();
        System.out.printf("  가장 긴 세그먼트        %.1fm%s%n", longest,
                localized ? " (공사에서 먼 구간)" : "");
        if (localized) {
            System.out.printf("  공사 주변 세그먼트      최대 %.1fm  ← 공사 1건이 막을 수 있는 최대 길이%n",
                    g.policy().nearM());
        } else {
            System.out.println("  ← 공사 1건이 막을 수 있는 최대 길이");
        }

        System.out.println();
        System.out.println("=== highway 분포 (way 기준) ===");
        g.highwayDist().entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                .forEach(e -> System.out.printf("  %-16s %d%n", e.getKey(), e.getValue()));
    }

    /**
     * steps 를 뺀 뒤 그래프가 몇 덩어리로 나뉘는지 본다.
     *
     * <p>중구 bbox 원본은 계단을 빼면 최대 덩어리가 전체의 92% 수준이고 나머지는 파편이다.
     * 즉 출발지와 도착지가 서로 다른 덩어리에 스냅되면 정상 동작인데도 '경로없음'이 나온다.
     * 검증 단계에서 알고리즘 버그와 헷갈리지 않도록 적재 전에 미리 찍어둔다.
     */
    private static void reportConnectivity(Graph g) {
        // 분할 노드는 OSM id 가 없어서 좌표를 키로 쓴다. 여기서는 연결 관계만 보면 되므로 이걸로 충분하다.
        Map<String, List<String>> adj = new HashMap<>(g.nodes().size() * 2);
        for (Segment s : g.segments()) {
            if (s.excludeReason() != null) {
                continue;
            }
            String from = s.from().coordKey();
            String to = s.to().coordKey();
            adj.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            adj.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
        }

        Set<String> seen = new HashSet<>(adj.size() * 2);
        List<Integer> sizes = new ArrayList<>();
        for (String start : adj.keySet()) {
            if (!seen.add(start)) {
                continue;
            }
            int size = 0;
            Deque<String> stack = new ArrayDeque<>();
            stack.push(start);
            while (!stack.isEmpty()) {
                String cur = stack.pop();
                size++;
                for (String next : adj.getOrDefault(cur, List.of())) {
                    if (seen.add(next)) {
                        stack.push(next);
                    }
                }
            }
            sizes.add(size);
        }
        sizes.sort((a, b) -> Integer.compare(b, a));

        int totalNodes = adj.size();
        System.out.println("  노드 " + totalNodes + "개 / 덩어리 " + sizes.size() + "개");
        if (!sizes.isEmpty()) {
            System.out.printf("  최대 덩어리 %d개 (%.1f%%)%n", sizes.get(0), 100.0 * sizes.get(0) / totalNodes);
            System.out.println("  상위 10개 " + sizes.subList(0, Math.min(10, sizes.size())));
            System.out.println("  ※ 서로 다른 덩어리 사이는 경로가 없는 게 정상입니다. 알고리즘 버그와 구분하세요.");
        }
    }

    // ------------------------------------------------------------------ 계산

    /**
     * 하버사인 거리(m). 중구 규모에서 측지선 공식과의 차이는 cm 단위라 경로 비교에 영향이 없다.
     * 경사도를 붙이는 다음 단계에서도 이 값이 '수평거리' 분모가 된다.
     */
    private static double haversineM(OsmNode a, OsmNode b) {
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.lon() - a.lon());

        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /**
     * 세그먼트가 {@code splitM} 보다 길면 균등 간격으로 중간 노드를 만든다.
     *
     * <p>좌표를 선형 보간한다. 수백 m 규모에서 대권 경로와의 차이는 mm 단위라 무시해도 된다.
     *
     * @return 중간 노드들(from → to 순). 쪼갤 필요가 없으면 빈 목록
     */
    private static List<OsmNode> splitPoints(OsmNode from, OsmNode to, double splitM) {
        if (splitM <= 0) {
            return List.of();
        }
        double length = haversineM(from, to);
        int parts = (int) Math.ceil(length / splitM);
        return interpolate(from, to, parts);
    }

    /** {@code parts} 등분하는 중간 노드들. 양 끝은 포함하지 않는다. */
    private static List<OsmNode> interpolate(OsmNode from, OsmNode to, int parts) {
        if (parts <= 1) {
            return List.of();
        }

        List<OsmNode> mids = new ArrayList<>(parts - 1);
        for (int i = 1; i < parts; i++) {
            double t = (double) i / parts;
            mids.add(new OsmNode(null,
                    from.lat() + (to.lat() - from.lat()) * t,
                    from.lon() + (to.lon() - from.lon()) * t));
        }
        return mids;
    }

    /**
     * 점 P 와 선분 AB 사이의 최단거리(m). 어떤 세그먼트가 공사 '주변'인지 판정하는 데 쓴다.
     *
     * <p>런타임의 {@code RouteGraph} 와 같은 평면 근사다. 이 도구는 Spring 없이 도는
     * 단독 실행 도구라 그쪽 코드를 가져다 쓸 수 없어 같은 식을 여기에도 둔다.
     */
    private static double pointToSegmentM(double pLat, double pLon,
                                          double aLat, double aLon, double bLat, double bLon) {
        double mPerDegLat = Math.PI * EARTH_RADIUS_M / 180.0;
        double mPerDegLon = mPerDegLat * Math.cos(Math.toRadians(pLat));

        double ax = (aLon - pLon) * mPerDegLon;
        double ay = (aLat - pLat) * mPerDegLat;
        double bx = (bLon - pLon) * mPerDegLon;
        double by = (bLat - pLat) * mPerDegLat;

        double dx = bx - ax;
        double dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0.0) {
            return Math.sqrt(ax * ax + ay * ay);
        }

        double t = Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / lenSq));
        double cx = ax + t * dx;
        double cy = ay + t * dy;
        return Math.sqrt(cx * cx + cy * cy);
    }

    /**
     * 잘게 쪼갤 기준이 될 공사 좌표를 DB 에서 읽는다.
     *
     * <p>읽기만 한다. DB 에 못 닿거나 CONSTRUCTION_ZONES 가 비어 있으면 빈 목록을 돌려주고,
     * 그 경우 전 구간이 {@code --split} 값으로 똑같이 쪼개진다.
     *
     * <p>종료된 공사는 제외한다. 이미 끝난 공사 주변을 잘게 쪼개봐야 노드만 늘어난다.
     */
    private static List<double[]> readConstructionPoints(String url, String user, String password, String region) {
        List<double[]> points = new ArrayList<>();
        String sql = "SELECT LATITUDE, LONGITUDE FROM CONSTRUCTION_ZONES "
                + "WHERE REGION_ID = ? AND LATITUDE IS NOT NULL AND LONGITUDE IS NOT NULL "
                + "AND (END_DATE IS NULL OR CURDATE() <= END_DATE)";

        try (Connection con = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, region);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    points.add(new double[]{rs.getDouble(1), rs.getDouble(2)});
                }
            }
        } catch (Exception e) {
            System.out.println("[안내] 공사 좌표를 읽지 못했습니다(" + e.getMessage() + ").");
            System.out.println("       전 구간을 같은 간격으로 쪼갭니다.");
            return List.of();
        }
        return points;
    }

    /** DECIMAL(10,7) 에 맞춘다. OSM 원본이 소수점 7자리라 반올림 손실이 없다. */
    private static BigDecimal coord(double v) {
        return BigDecimal.valueOf(v).setScale(7, RoundingMode.HALF_UP);
    }

    /** DECIMAL(10,2). cm 단위까지 남긴다. */
    private static BigDecimal meters(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ 유틸

    private static Path resolveJsonPath(Map<String, String> opt) {
        return Paths.get(opt.getOrDefault("json", DEFAULT_JSON));
    }

    /**
     * 분할 규칙을 만든다. 공사 좌표는 DB 에서 읽어오되, 못 읽으면 전 구간을 같은 간격으로 쪼갠다
     * (읽기만 하므로 dry-run 에서도 안전하다).
     */
    private static SplitPolicy resolveSplitPolicy(Map<String, String> opt) {
        double far = number(opt.get("split"), DEFAULT_SPLIT_M, "--split");
        double near = number(opt.get("split-near"), DEFAULT_SPLIT_NEAR_M, "--split-near");
        double radius = number(opt.get("split-near-radius"),
                DEFAULT_SPLIT_NEAR_RADIUS_M, "--split-near-radius");

        if (near <= 0 || near >= far) {
            return new SplitPolicy(far, 0, 0, List.of());
        }

        Properties app = readAppProperties(opt.getOrDefault("props", DEFAULT_PROPS));
        String region = opt.getOrDefault("region", app.getProperty("wheelway.region-id", DEFAULT_REGION));
        String url = opt.getOrDefault("url", app.getProperty("spring.datasource.url", DEFAULT_JDBC_URL));
        String user = opt.getOrDefault("user", app.getProperty("spring.datasource.username", DEFAULT_DB_USER));
        String password = opt.get("password") != null ? opt.get("password")
                : (System.getenv(ENV_DB_PASSWORD) != null ? System.getenv(ENV_DB_PASSWORD)
                : app.getProperty("spring.datasource.password"));

        if (password == null) {
            System.out.println("[안내] 비밀번호가 없어 공사 좌표를 읽지 못합니다. 전 구간을 같은 간격으로 쪼갭니다.");
            return new SplitPolicy(far, 0, 0, List.of());
        }
        return new SplitPolicy(far, near, radius, readConstructionPoints(url, user, password, region));
    }

    private static double number(String v, double fallback, String name) {
        if (v == null) {
            return fallback;
        }
        try {
            return Math.max(0, Double.parseDouble(v));
        } catch (NumberFormatException e) {
            System.out.println("[경고] " + name + "=" + v + " 를 읽지 못해 기본값 " + fallback + " 을 씁니다.");
            return fallback;
        }
    }

    /**
     * 설정을 읽는다. {@code application.properties} 를 먼저 읽고
     * {@code application-local.properties}(비밀값)를 그 위에 덮는다.
     * Spring 이 프로필로 하는 일을 직접 하는 것이다 — 이 도구는 Spring 을 띄우지 않아 {@code @Value} 를 못 쓴다.
     *
     * <p>{@code ${...}} 플레이스홀더는 해석하지 않는다. 값에 그걸 쓴 경우엔 환경변수나 인자를 써야 한다.
     */
    private static Properties readAppProperties(String path) {
        Properties props = new Properties();
        boolean base = merge(props, path);
        boolean local = merge(props, LOCAL_PROPS);

        if (!base && !local) {
            System.out.println("[안내] 설정 파일이 없습니다 — 인자/환경변수만 사용합니다.");
        } else if (!local) {
            System.out.println("[안내] " + LOCAL_PROPS + " 없음 — 비밀번호는 인자/환경변수로 주세요.");
        }
        return props;
    }

    /** @return 실제로 읽었으면 true */
    private static boolean merge(Properties into, String path) {
        Path file = Paths.get(path);
        if (!Files.exists(file)) {
            return false;
        }
        try (var in = Files.newInputStream(file)) {
            Properties loaded = new Properties();
            loaded.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            into.putAll(loaded);
            return true;
        } catch (Exception e) {
            System.out.println("[경고] " + path + " 를 읽지 못했습니다: " + e.getMessage());
            return false;
        }
    }

    private static int sum(int[] counts) {
        int total = 0;
        for (int c : counts) {
            // rewriteBatchedStatements 사용 시 드라이버가 SUCCESS_NO_INFO(-2) 를 줄 수 있다.
            total += (c >= 0) ? c : 1;
        }
        return total;
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

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? "" : v.asString();
    }
}

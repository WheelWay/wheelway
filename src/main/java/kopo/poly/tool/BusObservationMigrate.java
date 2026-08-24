package kopo.poly.tool;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 버스 관측 두 표를 <b>로컬에서 AWS 로 그대로 복사하는</b> 도구.
 * {@code BUS_LOW_FLOOR_SEEN} · {@code BUS_ROUTE_SPEED} 뿐이다.
 *
 * <p>Spring Bean 이 아니고 main 으로만 실행한다. 다른 단독 도구들과 같은 규칙이다.
 *
 * <h3>왜 이 두 표만인가</h3>
 * AWS 이관 범위를 정하면서 남은 것이 이 둘뿐이다(인계서 2026-08-24_3).
 * {@code NODES}/{@code EDGES} 는 {@link OsmGraphLoader} 가 다시 만들고,
 * 수동 엣지·계정·제보·보행기록은 새로 하기로 했다. 그런데 <b>이 둘은 코드로 못 만든다.</b>
 *
 * <ul>
 *   <li><b>저상 여부를 TAGO 노선 목록이 주지 않는다.</b> {@code vehicletp} 는 도착정보에만 있고,
 *       그건 '지금 오고 있는 그 차가 저상이냐' 다. "100번이 저상 노선인가" 는 100번 차가 올 때
 *       봐야만 알 수 있어서, 본 것을 쌓는 수밖에 없다.</li>
 *   <li><b>구간 소요시간을 TAGO 가 주지 않는다.</b> '몇 분 뒤 도착' 만 준다. 그래서 도착정보를
 *       연속 관측해 거리와 시간을 누적한다. 없으면 전 노선이 상수 19km/h 로 떨어지고,
 *       그 값은 실측 대비 <b>평균 27.9% 틀린다</b>(청주 61개 노선, 2026-08-22).</li>
 * </ul>
 *
 * <p><b>★ {@code @Scheduled} 가 없다.</b> 관측은 사용자가 검색할 때만 쌓인다.
 * "배포해두면 그 사이 알아서 쌓이겠지" 가 성립하지 않는다 — 아무도 안 쓰면 영영 안 채워지고,
 * 그동안 화면은 <b>"저상버스가 서는 정류장이 없습니다"</b> 라고 말한다. 사실이 아닌데 그렇게 뜬다.
 * 그래서 옮긴다.
 *
 * <h3>파일을 거치지 않는 이유</h3>
 * {@link ConstructionZoneBackup} 은 파일로 뜬다. 그건 <b>되돌릴 지점을 남기는 일</b>이라
 * 파일이 목적 자체다. 이건 <b>한 번 복사하는 일</b>이고, 원본이 로컬에 그대로 남아 있어서
 * 중간 파일이 안전망 역할을 하지 못한다. 오히려 "파일이 최신인가" 라는 물음이 하나 더 생긴다.
 *
 * <h3>실행 방법</h3>
 * <pre>
 *   // 미리보기 — 양쪽에 무엇이 있는지만 본다. 아무것도 안 쓴다
 *   BusObservationMigrate copy --url=jdbc:mariadb://&lt;rds&gt;:3306/wheelway --user=wheel
 *
 *   // 실행
 *   BusObservationMigrate copy --url=... --user=wheel --force
 *
 *   // 옮긴 뒤 대조. 인자 없이 돌리면 로컬을 센다
 *   BusObservationMigrate count
 *   BusObservationMigrate count --url=... --user=wheel
 * </pre>
 *
 * <p><b>비밀번호</b>: 대상은 {@code --password=} 또는 환경변수 {@code WHEELWAY_TARGET_PASSWORD},
 * 원본(로컬)은 {@code application.properties} 에서 읽는다.
 * 인자로 주면 IntelliJ Run Configuration 에 평문으로 남으므로 환경변수를 권한다.
 *
 * <p><b>★ 원본은 언제나 {@code application.properties} 의 DB 다.</b> 바꿀 수 없게 해뒀다 —
 * 양쪽을 다 옵션으로 열어두면 언젠가 <b>RDS 를 읽어 RDS 에 쓰거나 방향을 뒤집는</b> 실수가 나온다.
 * 관측은 누적값이라 방향이 뒤집히면 <b>실측이 빈 값으로 덮인다.</b> 되돌릴 수 없다.
 */
public final class BusObservationMigrate {

    private static final String DEFAULT_REGION = "chungbuk-cheongju";

    private static final String PROPS = "src/main/resources/application.properties";
    private static final String LOCAL_PROPS = "credentials/api_keys.properties";

    /** 대상(RDS) 비밀번호. 원본 것과 이름이 다르다 — 같으면 어느 쪽 값인지 헷갈린다. */
    private static final String ENV_TARGET_PASSWORD = "WHEELWAY_TARGET_PASSWORD";

    private static final int BATCH_SIZE = 500;

    private BusObservationMigrate() {
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        String mode = (args.length > 0 && !args[0].startsWith("--")) ? args[0] : "copy";
        Map<String, String> opt = parseOptions(args);

        switch (mode) {
            case "copy" -> copy(opt);
            case "count" -> count(opt);
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("사용법: BusObservationMigrate [copy|count] [옵션]");
        System.out.println("  copy   (기본) 로컬 → 대상 복사. --force 없으면 미리보기만");
        System.out.println("  count         지역별 행 수를 센다. 옮긴 뒤 대조용");
        System.out.println();
        System.out.println("옵션:");
        System.out.println("  --region=<id>   기본 " + DEFAULT_REGION);
        System.out.println("  --url=jdbc:mariadb://<rds>:3306/wheelway   대상 (copy 에 필수)");
        System.out.println("  --user=...");
        System.out.println("  --password=...  환경변수 " + ENV_TARGET_PASSWORD + " 도 가능");
        System.out.println("  --force         실제로 쓴다. 없으면 양쪽 현황만 보여준다");
        System.out.println();
        System.out.println("  ★ 원본은 언제나 " + PROPS + " 의 DB 다. 방향은 바꿀 수 없다");
    }

    // --------------------------------------------------------------- copy

    private static void copy(Map<String, String> opt) throws Exception {
        Properties app = readProperties();
        String region = region(opt, app);

        Db source = Db.source(app);
        Db target = Db.target(app, opt);
        if (source == null || target == null) {
            return;
        }
        if (source.url.equals(target.url)) {
            // 같은 DB 를 가리키면 DELETE 후 자기 자신을 다시 넣는 꼴이다. 결과는 같지만
            // 하려던 일이 아닌 것이 분명하므로 멈춘다.
            System.out.println("[중단] 원본과 대상이 같은 DB 입니다: " + source.url);
            System.out.println("       --url 에 RDS 주소를 주세요.");
            return;
        }

        System.out.println("=== 복사 === region=" + region);
        System.out.println("  원본 " + source.url);
        System.out.println("  대상 " + target.url + " (user=" + target.user + ")");
        System.out.println();

        List<Seen> seen;
        List<Speed> speed;
        try (Connection con = DriverManager.getConnection(source.url, source.user, source.password)) {
            seen = readSeen(con, region);
            speed = readSpeed(con, region);
        }

        long lowRoutes = seen.stream().filter(s -> s.lowCnt() > 0).count();
        long thin = speed.stream().filter(s -> s.seconds() < 1).count();

        System.out.println("  [원본] BUS_LOW_FLOOR_SEEN " + seen.size() + "행 (저상 노선 " + lowRoutes + "개)");
        System.out.println("         BUS_ROUTE_SPEED    " + speed.size() + "행"
                + (thin > 0 ? " (그중 " + thin + "행은 누적 1초 미만이라 서비스가 안 쓴다)" : ""));

        if (seen.isEmpty() && speed.isEmpty()) {
            System.out.println();
            System.out.println("[중단] 옮길 것이 없습니다. region 을 잘못 줬을 수 있습니다.");
            return;
        }

        // 대상에 이미 있는 것을 먼저 보여준다. 덮어쓰는 것이 무엇인지 모르고 --force 를
        // 붙이는 일을 막는다 — 관측은 누적값이라 덮어쓰면 되돌릴 수가 없다.
        int[] before;
        try (Connection con = DriverManager.getConnection(target.url, target.user, target.password)) {
            before = countRegion(con, region);
        } catch (SQLException e) {
            System.out.println();
            System.out.println("[실패] 대상에 붙지 못했습니다: " + e.getMessage());
            System.out.println("       RDS 보안그룹이 이 PC 를 막고 있거나, 스키마가 아직 없습니다.");
            System.out.println("       스키마는 data/wheelchair_ddl_aws_20260824.sql 로 만듭니다.");
            return;
        }
        System.out.println("  [대상] BUS_LOW_FLOOR_SEEN " + before[0] + "행 / BUS_ROUTE_SPEED " + before[1] + "행");

        if (!opt.containsKey("force")) {
            System.out.println();
            System.out.println("[미리보기] 아무것도 쓰지 않았습니다. 실행하려면 --force 를 주세요.");
            if (before[0] > 0 || before[1] > 0) {
                System.out.println("           ★ 대상에 이미 관측이 있습니다. 그 region 은 지워지고 원본 값으로 바뀝니다.");
            }
            return;
        }

        int wrote;
        try (Connection con = DriverManager.getConnection(target.url, target.user, target.password)) {
            con.setAutoCommit(false);
            try {
                wrote = write(con, region, seen, speed);
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                // 한 표만 들어가고 다른 표가 실패하면 저상은 아는데 속도는 모르는 상태가 된다.
                // 그 상태는 화면상 '정상' 으로 보여서 알아채기가 어렵다. 그래서 통째로 되돌린다.
                System.out.println();
                System.out.println("[실패] 되돌렸습니다: " + e.getMessage());
                return;
            }
        }

        System.out.println();
        System.out.println("  넣은 행 " + wrote + " (지운 행은 세지 않는다)");
        System.out.println();
        System.out.println("★ 서버를 재기동해야 반영됩니다. BusService 는 기동할 때 한 번 읽어");
        System.out.println("  메모리에 들고 있습니다 — 돌고 있는 서버는 이 값을 다시 읽지 않습니다.");
    }

    /**
     * 대상의 그 region 을 비우고 원본 값을 넣는다.
     *
     * <p><b>UPSERT 가 아니라 DELETE 후 INSERT 인 이유</b>: 관측은 누적값이라
     * 두 번 돌렸을 때 값이 불어나는 것이 제일 무섭다 — 화면에 오류가 안 뜨고
     * 속도만 조용히 틀려진다. 지우고 넣으면 몇 번을 돌려도 결과가 같다.
     * 빈 RDS 에서는 DELETE 가 0건이라 아무 일도 안 한다.
     */
    private static int write(Connection con, String region, List<Seen> seen, List<Speed> speed)
            throws SQLException {

        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM BUS_LOW_FLOOR_SEEN WHERE REGION_ID = ?")) {
            ps.setString(1, region);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM BUS_ROUTE_SPEED WHERE REGION_ID = ?")) {
            ps.setString(1, region);
            ps.executeUpdate();
        }

        int rows = 0;

        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO BUS_LOW_FLOOR_SEEN"
                        + " (REGION_ID, ROUTE_NO, LOW_CNT, TOTAL_CNT, FIRST_SEEN, LAST_SEEN)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            int n = 0;
            for (Seen s : seen) {
                ps.setString(1, region);
                ps.setString(2, s.routeNo());
                ps.setInt(3, s.lowCnt());
                ps.setInt(4, s.totalCnt());
                ps.setTimestamp(5, s.firstSeen());
                ps.setTimestamp(6, s.lastSeen());
                ps.addBatch();
                if (++n % BATCH_SIZE == 0) {
                    rows += sum(ps.executeBatch());
                }
            }
            rows += sum(ps.executeBatch());
        }

        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO BUS_ROUTE_SPEED"
                        + " (REGION_ID, ROUTE_NO, BUCKET, METERS, SECONDS, FIRST_SEEN, LAST_SEEN)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            int n = 0;
            for (Speed s : speed) {
                ps.setString(1, region);
                ps.setString(2, s.routeNo());
                ps.setInt(3, s.bucket());
                ps.setDouble(4, s.meters());
                ps.setDouble(5, s.seconds());
                ps.setTimestamp(6, s.firstSeen());
                ps.setTimestamp(7, s.lastSeen());
                ps.addBatch();
                if (++n % BATCH_SIZE == 0) {
                    rows += sum(ps.executeBatch());
                }
            }
            rows += sum(ps.executeBatch());
        }

        return rows;
    }

    // -------------------------------------------------------------- count

    /**
     * 옮긴 뒤 양쪽을 눈으로 맞춰보는 용도다. <b>지역별로</b> 세는 이유는
     * {@code REGION_ID} 가 틀린 채로 들어가도 합계는 맞아 보이기 때문이다.
     *
     * <p>{@code NODES}/{@code EDGES} 를 같이 세는 것은 인계서 2026-08-24_3 의 7-1 때문이다 —
     * application.properties(64,549/142,450)와 AWS DDL 머리말(32,301/70,956)이 정확히 두 배
     * 차이라, 중복 적재인지 주석이 틀린 것인지 아직 확인하지 않았다.
     */
    private static void count(Map<String, String> opt) throws Exception {
        Properties app = readProperties();

        Db db = opt.containsKey("url") ? Db.target(app, opt) : Db.source(app);
        if (db == null) {
            return;
        }

        System.out.println("=== 행 수 === " + db.url);
        try (Connection con = DriverManager.getConnection(db.url, db.user, db.password)) {
            for (String table : List.of("NODES", "EDGES",
                    "BUS_LOW_FLOOR_SEEN", "BUS_ROUTE_SPEED",
                    "MANUAL_EDGES", "CONSTRUCTION_ZONES", "USERS")) {
                countOne(con, table);
            }
        }
    }

    /** {@code USERS} 처럼 REGION_ID 가 없는 표도 있어서, 지역별로 먼저 세보고 안 되면 전체를 센다. */
    private static void countOne(Connection con, String table) {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT REGION_ID, COUNT(*) FROM " + table
                     + " GROUP BY REGION_ID ORDER BY REGION_ID")) {
            boolean any = false;
            while (rs.next()) {
                System.out.printf("  %-20s %-20s %,10d%n", table, rs.getString(1), rs.getLong(2));
                any = true;
            }
            if (!any) {
                System.out.printf("  %-20s %-20s %,10d%n", table, "(비어 있음)", 0);
            }
        } catch (SQLException e) {
            countAll(con, table);
        }
    }

    private static void countAll(Connection con, String table) {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (rs.next()) {
                System.out.printf("  %-20s %-20s %,10d%n", table, "(지역 없음)", rs.getLong(1));
            }
        } catch (SQLException e) {
            System.out.printf("  %-20s %s%n", table, "[없음] " + e.getMessage());
        }
    }

    private static int[] countRegion(Connection con, String region) throws SQLException {
        return new int[]{
                countRegion(con, "BUS_LOW_FLOOR_SEEN", region),
                countRegion(con, "BUS_ROUTE_SPEED", region)};
    }

    private static int countRegion(Connection con, String table, String region) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE REGION_ID = ?")) {
            ps.setString(1, region);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // --------------------------------------------------------------- 읽기

    private static List<Seen> readSeen(Connection con, String region) throws SQLException {
        List<Seen> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT ROUTE_NO, LOW_CNT, TOTAL_CNT, FIRST_SEEN, LAST_SEEN"
                        + "  FROM BUS_LOW_FLOOR_SEEN WHERE REGION_ID = ? ORDER BY ROUTE_NO")) {
            ps.setString(1, region);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Seen(rs.getString(1), rs.getInt(2), rs.getInt(3),
                            rs.getTimestamp(4), rs.getTimestamp(5)));
                }
            }
        }
        return out;
    }

    private static List<Speed> readSpeed(Connection con, String region) throws SQLException {
        List<Speed> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT ROUTE_NO, BUCKET, METERS, SECONDS, FIRST_SEEN, LAST_SEEN"
                        + "  FROM BUS_ROUTE_SPEED WHERE REGION_ID = ? ORDER BY ROUTE_NO, BUCKET")) {
            ps.setString(1, region);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Speed(rs.getString(1), rs.getInt(2), rs.getDouble(3),
                            rs.getDouble(4), rs.getTimestamp(5), rs.getTimestamp(6)));
                }
            }
        }
        return out;
    }

    // --------------------------------------------------------------- 공통

    private record Seen(String routeNo, int lowCnt, int totalCnt,
                        Timestamp firstSeen, Timestamp lastSeen) {
    }

    private record Speed(String routeNo, int bucket, double meters, double seconds,
                         Timestamp firstSeen, Timestamp lastSeen) {
    }

    private record Db(String url, String user, String password) {

        /** 원본은 언제나 로컬이다. 옵션으로 바꿀 수 없다(클래스 주석 참고). */
        static Db source(Properties app) {
            String url = app.getProperty("spring.datasource.url");
            String user = app.getProperty("spring.datasource.username");
            String password = app.getProperty("spring.datasource.password");

            if (url == null || user == null || password == null) {
                System.out.println("[중단] 원본 접속 정보를 찾지 못했습니다. "
                        + PROPS + " / " + LOCAL_PROPS + " 를 확인하세요.");
                return null;
            }
            return new Db(url, user, password);
        }

        static Db target(Properties app, Map<String, String> opt) {
            String url = opt.get("url");
            if (url == null) {
                System.out.println("[중단] --url=jdbc:mariadb://<rds>:3306/wheelway 가 필요합니다.");
                return null;
            }

            // 사용자를 안 주면 원본 것을 쓴다. RDS 를 같은 계정명으로 만드는 경우가 흔하다.
            String user = opt.getOrDefault("user", app.getProperty("spring.datasource.username"));

            String password = opt.get("password");
            if (password == null) {
                password = System.getenv(ENV_TARGET_PASSWORD);
            }
            if (user == null || password == null) {
                System.out.println("[중단] 대상 접속 정보가 모자랍니다.");
                System.out.println("       --user= 와 --password= (또는 환경변수 "
                        + ENV_TARGET_PASSWORD + ") 가 필요합니다.");
                System.out.println("       ★ 대상 비밀번호는 " + PROPS + " 에서 가져오지 않습니다 —");
                System.out.println("         로컬 비밀번호로 RDS 에 붙는 일을 막기 위해서입니다.");
                return null;
            }
            return new Db(url, user, password);
        }
    }

    private static int sum(int[] counts) {
        int n = 0;
        for (int c : counts) {
            // 드라이버가 행 수 대신 SUCCESS_NO_INFO(-2) 를 줄 수 있다. 그때는 1건으로 센다.
            n += (c < 0) ? 1 : c;
        }
        return n;
    }

    private static String region(Map<String, String> opt, Properties app) {
        return opt.getOrDefault("region", app.getProperty("wheelway.region-id", DEFAULT_REGION));
    }

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

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> opt = new HashMap<>();
        for (String a : Arrays.asList(args)) {
            if (!a.startsWith("--")) {
                continue;
            }
            String body = a.substring(2);
            int eq = body.indexOf('=');
            if (eq < 0) {
                opt.put(body, "");
            } else {
                opt.put(body.substring(0, eq), body.substring(eq + 1));
            }
        }
        return opt;
    }
}

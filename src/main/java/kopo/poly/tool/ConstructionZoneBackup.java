package kopo.poly.tool;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigDecimal;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * CONSTRUCTION_ZONES 의 좌표·반경을 파일로 뜨고 되돌리는 도구.
 *
 * <p>{@link ConstructionZoneLoader} 와 마찬가지로 Spring Bean 이 아니고 main 메서드로만 실행한다.
 *
 * <h3>왜 필요한가</h3>
 * CONSTRUCTION_ZONES 에는 지오코딩 원본을 보관하는 컬럼이 없다. {@code /admin.html} 의 저장은
 * LATITUDE/LONGITUDE/BLOCK_RADIUS_M 을 그대로 덮어쓰는 UPDATE 라서, 한 번 저장하면
 * 그 전 값으로 되돌릴 수단이 없다. 유일한 대안인 {@code ConstructionZoneLoader load --force} 는
 * region 전체를 지우고 다시 넣기 때문에 <b>수동 수정이 전부 날아가고 ID 도 새로 매겨진다</b>.
 * 그래서 수정 전 상태를 파일로 남긴다.
 *
 * <h3>기존 파일은 절대 덮어쓰지 않는다</h3>
 * 같은 날 두 번 돌리면 {@code _2}, {@code _3} 이 붙는다. 백업이 자기 자신을 덮어쓰면
 * 안전망이 아니라 <b>지연된 삭제</b>가 된다 — 실수를 깨닫는 시점이 갱신 주기보다 늦으면
 * 원본이 조용히 사라진다. 파일이 쌓이는 편이 낫다. 59행짜리 텍스트라 한 장에 8KB 남짓이다.
 *
 * <h3>날짜 필터를 걸지 않는 이유</h3>
 * 화면과 하드필터는 공사기간이 지난 행을 제외하지만({@code CURDATE() <= END_DATE}),
 * 백업은 <b>전 행</b>을 뜬다. 되돌릴 대상은 '지금 보이는 것'이 아니라 '테이블에 있는 것'이다.
 * 종료된 공사도 {@code -- [종료]} 로 표시만 하고 같이 넣는다.
 *
 * <h3>실행 방법</h3>
 * <pre>
 *   // 백업 (기본). data/construction_zones_backup_YYYYMMDD.sql 생성
 *   ConstructionZoneBackup
 *   ConstructionZoneBackup backup
 *
 *   // 복원 — 파일만 읽고 무엇이 바뀔지 보여준다. DB 는 안 건드린다
 *   ConstructionZoneBackup restore --file=data/construction_zones_backup_20260810.sql
 *
 *   // 복원 실행
 *   ConstructionZoneBackup restore --file=... --force
 * </pre>
 *
 * <p>인자 없이 실행하면 백업이라 실수로 DB 를 건드리지 않는다.
 * 복원은 {@code --force} 없이는 미리보기만 한다.
 *
 * <p><b>복원 후에는 서버의 차단 Set 을 다시 계산해야 반영된다.</b> 서버를 재기동하거나,
 * {@code /admin.html} 에서 아무 공사나 값을 바꾸지 않고 저장하면 된다.
 */
public final class ConstructionZoneBackup {

    private static final String DEFAULT_REGION = "seoul-junggu";

    private static final String PROPS = "src/main/resources/application.properties";
    private static final String LOCAL_PROPS = "credentials/api_keys.properties";

    private static final String OUT_DIR = "data";
    private static final String BASE_NAME = "construction_zones_backup_";

    /** 공사명이 길어 한 줄을 넘기면 파일이 읽기 어려워진다. 어느 건인지 알아볼 정도면 충분하다. */
    private static final int COMMENT_MAX = 45;

    private ConstructionZoneBackup() {
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        String mode = (args.length > 0 && !args[0].startsWith("--")) ? args[0] : "backup";
        Map<String, String> opt = parseOptions(args);

        switch (mode) {
            case "backup" -> backup(opt);
            case "restore" -> restore(opt);
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("사용법: ConstructionZoneBackup [backup|restore] [옵션]");
        System.out.println("  backup  (기본) 좌표·반경을 새 파일로 뜬다. 기존 파일은 안 건드림");
        System.out.println("  restore        파일의 값으로 되돌린다");
        System.out.println();
        System.out.println("옵션:");
        System.out.println("  --region=<id>  기본 " + DEFAULT_REGION);
        System.out.println("  --file=<path>  restore 대상 파일 (restore 에 필수)");
        System.out.println("  --force        restore 를 실제로 실행. 없으면 미리보기만");
    }

    // --------------------------------------------------------- backup

    private static void backup(Map<String, String> opt) throws Exception {
        Properties app = readProperties();
        String region = region(opt, app);

        Db db = Db.from(app);
        if (db == null) {
            return;
        }

        List<Row> rows = new ArrayList<>();
        try (Connection con = DriverManager.getConnection(db.url, db.user, db.password);
             PreparedStatement ps = con.prepareStatement(
                     "SELECT ID, NAME, LATITUDE, LONGITUDE, BLOCK_RADIUS_M, NOTE_LINE_JSON"
                             + "     , (END_DATE IS NOT NULL AND END_DATE < CURDATE()) AS EXPIRED"
                             + "  FROM CONSTRUCTION_ZONES"
                             + " WHERE REGION_ID = ?"
                             + " ORDER BY ID")) {

            ps.setString(1, region);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong("ID"), rs.getString("NAME"),
                            rs.getBigDecimal("LATITUDE"), rs.getBigDecimal("LONGITUDE"),
                            rs.getBigDecimal("BLOCK_RADIUS_M"), rs.getString("NOTE_LINE_JSON"),
                            rs.getBoolean("EXPIRED")));
                }
            }
        }

        if (rows.isEmpty()) {
            System.out.println("[중단] region='" + region + "' 에 행이 없습니다.");
            return;
        }

        List<String[]> manual = readManualEdges(db, region);

        Path out = nextFreePath();
        Files.writeString(out, render(rows, region) + renderManual(manual, region), StandardCharsets.UTF_8);

        long expired = rows.stream().filter(r -> r.expired).count();
        long noCoord = rows.stream().filter(r -> r.lat == null || r.lon == null).count();

        System.out.println("=== 백업 === region=" + region);
        System.out.println("  CONSTRUCTION_ZONES " + rows.size() + "행 → " + out);
        System.out.println("  그중 기간 종료 " + expired + "행, 좌표 없음 " + noCoord + "행");
        System.out.println("  MANUAL_EDGES " + manual.size() + "행");
        System.out.println();
        System.out.println("되돌리려면: ConstructionZoneBackup restore --file=" + out + " --force");
    }

    /**
     * 아직 없는 파일 이름을 고른다. 같은 날 두 번 돌려도 앞의 것을 지우지 않기 위한 것이다.
     * 백업이 백업을 덮어쓰는 순간 되돌릴 지점이 하나 사라진다.
     */
    private static Path nextFreePath() {
        String day = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path dir = Paths.get(OUT_DIR);

        Path first = dir.resolve(BASE_NAME + day + ".sql");
        if (!Files.exists(first)) {
            return first;
        }
        for (int n = 2; ; n++) {
            Path p = dir.resolve(BASE_NAME + day + "_" + n + ".sql");
            if (!Files.exists(p)) {
                return p;
            }
        }
    }

    private static String render(List<Row> rows, String region) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder sb = new StringBuilder();

        sb.append("-- ============================================================\n");
        sb.append("-- CONSTRUCTION_ZONES 좌표·반경 백업 — ").append(region).append('\n');
        sb.append("-- 뽑은 시각: ").append(now).append('\n');
        sb.append("-- 행 수: ").append(rows.size()).append(" (기간 종료분 포함, 날짜 필터 없음)\n");
        sb.append("-- 만든 것: ConstructionZoneBackup\n");
        sb.append("-- ============================================================\n");
        sb.append("--\n");
        sb.append("-- 이 파일은 '뽑은 시각의 상태' 다. 지오코딩 원본이라는 뜻이 아니다 —\n");
        sb.append("-- 그 전에 수동 수정이 있었다면 그 결과가 그대로 담겨 있다.\n");
        sb.append("--\n");
        sb.append("-- 되돌리는 법\n");
        sb.append("--   ConstructionZoneBackup restore --file=<이 파일>          미리보기\n");
        sb.append("--   ConstructionZoneBackup restore --file=<이 파일> --force  실행\n");
        sb.append("--\n");
        sb.append("--   DB 클라이언트가 있으면 이 파일을 그대로 실행해도 된다.\n");
        sb.append("--   복원 후에는 서버의 차단 Set 을 다시 계산해야 반영된다 —\n");
        sb.append("--   서버를 재기동하거나, /admin.html 에서 아무 공사나 값을 바꾸지 않고 저장한다.\n");
        sb.append("-- ============================================================\n\n");

        for (Row r : rows) {
            if (r.lat == null || r.lon == null) {
                // 좌표가 NULL 인 행은 되돌릴 값이 없다. 지우지 않고 흔적만 남긴다.
                sb.append("-- [좌표없음] ID=").append(r.id).append("  ").append(comment(r.name)).append('\n');
                continue;
            }
            sb.append("UPDATE CONSTRUCTION_ZONES SET LATITUDE=").append(plain(r.lat))
                    .append(", LONGITUDE=").append(plain(r.lon))
                    .append(", BLOCK_RADIUS_M=").append(r.radius == null ? "NULL" : plain(r.radius))
                    .append(", NOTE_LINE_JSON=").append(quote(r.noteLine))
                    .append(" WHERE ID=").append(r.id)
                    .append(" AND REGION_ID='").append(region).append("';")
                    .append("   -- ").append(r.expired ? "[종료] " : "").append(comment(r.name))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 사람이 직접 넣고 뺀 엣지. 여기 없으면 복원할 때 <b>손으로 찍은 것이 전부 사라진다.</b>
     * ID 는 뜨지 않는다 — AUTO_INCREMENT 이고, 이 테이블은 좌표로만 동작하므로 값이 의미가 없다.
     */
    private static List<String[]> readManualEdges(Db db, String region) throws Exception {
        List<String[]> out = new ArrayList<>();
        try (Connection con = DriverManager.getConnection(db.url, db.user, db.password);
             PreparedStatement ps = con.prepareStatement(
                     "SELECT ACTION, FROM_LAT, FROM_LNG, TO_LAT, TO_LNG, EDGE_KIND, NOTE"
                             + "  FROM MANUAL_EDGES WHERE REGION_ID = ? ORDER BY ID")) {
            ps.setString(1, region);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{
                            rs.getString(1), plain(rs.getBigDecimal(2)), plain(rs.getBigDecimal(3)),
                            plain(rs.getBigDecimal(4)), plain(rs.getBigDecimal(5)),
                            rs.getString(6), rs.getString(7)});
                }
            }
        } catch (SQLException e) {
            // 테이블이 아직 없는 환경(부록 4 미적용)에서도 백업 자체는 되게 둔다.
            System.out.println("[경고] MANUAL_EDGES 를 읽지 못했습니다: " + e.getMessage());
        }
        return out;
    }

    private static String renderManual(List<String[]> rows, String region) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n-- ============================================================\n");
        sb.append("-- MANUAL_EDGES — 사람이 직접 넣고 뺀 엣지 ").append(rows.size()).append("행\n");
        sb.append("-- 복원은 '이 region 을 통째로 이 시점으로' 되돌린다(DELETE 후 INSERT).\n");
        sb.append("-- ID 는 복원하지 않는다 — AUTO_INCREMENT 이고 좌표로만 동작하므로 의미가 없다.\n");
        sb.append("-- ============================================================\n");
        sb.append("DELETE FROM MANUAL_EDGES WHERE REGION_ID='").append(region).append("';\n");

        for (String[] r : rows) {
            sb.append("INSERT INTO MANUAL_EDGES (REGION_ID, ACTION, FROM_LAT, FROM_LNG, TO_LAT, TO_LNG, EDGE_KIND, NOTE)")
                    .append(" VALUES ('").append(region).append("', ").append(quote(r[0])).append(", ")
                    .append(r[1]).append(", ").append(r[2]).append(", ")
                    .append(r[3]).append(", ").append(r[4]).append(", ")
                    .append(quote(r[5])).append(", ").append(quote(r[6])).append(");\n");
        }
        return sb.toString();
    }

    // --------------------------------------------------------- restore

    private static void restore(Map<String, String> opt) throws Exception {
        String file = opt.get("file");
        if (file == null) {
            System.out.println("[중단] --file=<path> 가 필요합니다.");
            printUsage();
            return;
        }

        Path path = Paths.get(file);
        if (!Files.exists(path)) {
            System.out.println("[중단] 파일이 없습니다: " + path);
            return;
        }

        // 이 도구가 만든 형식만 이해한다. 한 줄에 한 문장, 세미콜론 뒤는 주석이다.
        // MANUAL_EDGES 는 DELETE 후 INSERT 라 파일에 적힌 순서를 그대로 지켜야 한다.
        List<String> stmts = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(s -> s.startsWith("UPDATE CONSTRUCTION_ZONES")
                        || s.startsWith("DELETE FROM MANUAL_EDGES")
                        || s.startsWith("INSERT INTO MANUAL_EDGES"))
                .map(ConstructionZoneBackup::sqlOf)
                .toList();

        if (stmts.isEmpty()) {
            System.out.println("[중단] " + path + " 에서 UPDATE 문을 찾지 못했습니다.");
            return;
        }

        System.out.println("=== 복원 === " + path);
        System.out.println("  UPDATE " + stmts.size() + "건");

        if (!opt.containsKey("force")) {
            System.out.println();
            System.out.println("[미리보기] DB 는 건드리지 않았습니다. 실행하려면 --force 를 주세요.");
            return;
        }

        // 복원은 '지금 DB' 를 '파일 시점' 으로 되돌린다. 그 사이에 누가 /admin.html 에서
        // 저장했다면 그 수정도 같이 사라진다. 화면을 열어둔 채 복원하면 실제로 이렇게 된다.
        System.out.println();
        System.out.println("[주의] 파일을 뜬 뒤에 저장된 수정이 있다면 그것도 되돌아갑니다.");
        System.out.println("       /admin.html 을 열어둔 사람이 없는지 확인하세요.");
        System.out.println();

        Properties app = readProperties();
        Db db = Db.from(app);
        if (db == null) {
            return;
        }

        int applied = 0;
        int missing = 0;
        try (Connection con = DriverManager.getConnection(db.url, db.user, db.password)) {
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                for (String sql : stmts) {
                    int n = st.executeUpdate(sql);
                    // UPDATE 가 0 이면 그 ID 가 없다는 뜻이다(재적재로 ID 가 바뀐 경우).
                    // 값이 이미 같아도 1 이 나온다 — 드라이버가 '바뀐 행'이 아니라 '찾은 행'을 센다.
                    // 따라서 '적용 N건' 은 N 건이 실제로 달라졌다는 뜻이 아니다.
                    // DELETE 는 지울 게 없으면 0 이 정상이라 실패로 세지 않는다.
                    if (n == 0 && sql.startsWith("UPDATE")) {
                        missing++;
                    } else {
                        applied++;
                    }
                }
            }
            con.commit();
        }

        System.out.println("  적용 " + applied + "건" + (missing > 0 ? ", ID 를 찾지 못함 " + missing + "건" : ""));
        if (missing > 0) {
            System.out.println("  [경고] ID 가 안 맞습니다. 백업 이후 load --force 로 재적재해");
            System.out.println("         ID 가 새로 매겨졌을 수 있습니다.");
        }
        System.out.println();
        System.out.println("차단 Set 을 다시 계산해야 반영됩니다 — 서버 재기동, 또는");
        System.out.println("/admin.html 에서 아무 공사나 값을 바꾸지 않고 저장하세요.");
    }

    // --------------------------------------------------------- 공통

    private record Row(long id, String name, BigDecimal lat, BigDecimal lon,
                       BigDecimal radius, String noteLine, boolean expired) {
    }

    /**
     * 문자열 컬럼을 SQL 리터럴로 만든다. {@code null} 은 {@code NULL} 이다 —
     * 빈 문자열로 바꾸면 '선을 지운 상태'와 '선이 없던 상태'가 구분되지 않는다.
     *
     * <p>NOTE_LINE_JSON 은 이 도구와 화면이 만든 좌표 배열이라 따옴표가 들어갈 일이 없지만,
     * 손으로 고칠 수 있는 파일이므로 이스케이프는 해둔다.
     */
    private static String quote(String s) {
        if (s == null) {
            return "NULL";
        }
        return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private record Db(String url, String user, String password) {

        static Db from(Properties app) {
            String url = app.getProperty("spring.datasource.url");
            String user = app.getProperty("spring.datasource.username");
            String password = app.getProperty("spring.datasource.password");

            if (url == null || user == null || password == null) {
                System.out.println("[중단] DB 접속 정보를 찾지 못했습니다. "
                        + PROPS + " / " + LOCAL_PROPS + " 를 확인하세요.");
                return null;
            }
            return new Db(url, user, password);
        }
    }

    /**
     * 한 줄에서 순수 SQL 만 잘라낸다. 형식은 {@code UPDATE ...;   -- 공사명} 이다.
     *
     * <p>단순히 첫 {@code ;} 에서 자르면 안 된다 — NOTE_LINE_JSON 이 따옴표 안에 들어가면서
     * 문자열 컬럼이 생겼기 때문이다. 주석 마커를 기준으로 자르고, 없으면 끝의 {@code ;} 만 뗀다.
     */
    private static String sqlOf(String line) {
        int mark = line.indexOf(";   --");
        String sql = (mark >= 0) ? line.substring(0, mark) : line.trim();
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
    }

    /**
     * {@code 5.00} 이 아니라 {@code 5} 로 적는다. 사람이 읽고 고칠 파일이다.
     * {@code null} 은 빈 문자열이 아니라 {@code NULL} 이다 — 값이 없는 것과 0 은 다르다.
     */
    private static String plain(BigDecimal v) {
        return v == null ? "NULL" : v.stripTrailingZeros().toPlainString();
    }

    /** 공사명을 한 줄 주석에 넣을 수 있게 다듬는다. 줄바꿈이 섞이면 파일이 깨진다. */
    private static String comment(String name) {
        String s = (name == null ? "" : name).replaceAll("\\s+", " ").trim();
        return s.length() > COMMENT_MAX ? s.substring(0, COMMENT_MAX) : s;
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

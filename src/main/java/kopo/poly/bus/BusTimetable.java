package kopo.poly.bus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * 지역 하나의 버스 운행 시간표. 공공데이터포털 CSV 를 그대로 읽는다.
 *
 * <p><b>왜 필요한가</b>: 도착정보는 <b>지금 굴러가고 있는 버스만</b> 잡는다.
 * 보은의 저상 5개 노선(330·340·410·610·620)은 전부 최근 도입된 전기버스인데
 * BIS 단말이 아직 연동되지 않아 <b>실시간에 한 번도 잡히지 않는다</b>.
 * 그 노선이 정확히 휠체어로 탈 수 있는 유일한 버스라, 실시간만 믿으면
 * 우리 화면은 사용자가 탈 수 있는 차에 대해 영원히 아무 말도 못 한다.
 * 시간표가 그 자리를 메운다 — "다음 340번은 12:55" 는 시간표로만 말할 수 있다.
 *
 * <p><b>원본을 손대지 않고 그대로 읽는 이유</b>: 다듬은 표를 따로 만들어 두면
 * 갱신이 왔을 때 무엇을 어떻게 고쳤는지 아무도 기억하지 못한다. 지저분한 값은
 * 여기서 흡수하고, 파일은 받은 그대로 갈아끼우면 되게 둔다.
 *
 * <p><b>★ 노선번호로만 이어진다.</b> 이 표는 지역명 기준(<i>보은 → 미원</i>)이고
 * TAGO 는 정류장 ID 기준이라, 둘을 잇는 열쇠는 노선번호뿐이다. 그래서 이 표는
 * <b>기점·종점 출발 시각</b>까지만 말할 수 있고 <b>'이 정류장에 몇 시'</b> 는 말하지 못한다.
 * 화면도 그렇게 적어야 한다 — 기점 출발 시각을 정류장 도착 시각처럼 보여주면
 * 사용자는 이미 지나간 버스를 기다리게 된다.
 *
 * @see BusTimetable#runMin(String) 편도 소요시간 — 복합 경로에서 쓸 재료다
 */
@Slf4j
public final class BusTimetable {

    /**
     * 시각 하나. 원문에 붙어 있던 단서를 같이 들고 다닌다.
     *
     * @param min   자정부터 몇 분. {@code 09:55} 는 595
     * @param days  이 편이 다니는 날. {@link Days#ALL} 이 대부분이다
     * @param note  원문에 붙어 있던 말(<i>법주 경유</i>, <i>학생수송</i>). 없으면 빈 문자열.
     *              해석하지 않고 그대로 넘긴다 — 우리가 못 알아본 단서를 사람은 알아본다
     */
    public record Departure(int min, Days days, String note) {

        /** {@code HH:mm}. 24시를 넘긴 값은 원본에 없어서 다루지 않는다. */
        public String hhmm() {
            return String.format("%02d:%02d", min / 60, min % 60);
        }
    }

    /** 다니는 날. 원문 단서에서 읽어낸다. */
    public enum Days {
        /** 단서가 없다 — 대부분이 여기다. */
        ALL,
        /** <i>평일 운행</i> */
        WEEKDAY,
        /** <i>주말,휴일 운행</i> */
        WEEKEND;

        boolean runsOn(DayOfWeek d) {
            boolean weekend = d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
            return switch (this) {
                case ALL -> true;
                case WEEKDAY -> !weekend;
                case WEEKEND -> weekend;
            };
        }
    }

    /**
     * 노선 한 개의 시간표.
     *
     * @param routeNo     노선번호. TAGO 와 이어지는 유일한 열쇠다
     * @param originName  기점 이름(보은)
     * @param destName    종점 이름. 원문이 자간을 벌려 놨다(<i>미 원</i>) — 붙여서 담는다
     * @param fromOrigin  기점에서 떠나는 편
     * @param fromDest    종점에서 떠나는 편(돌아오는 길)
     * @param runMin      편도 소요시간(분). 알아낼 수 없으면 {@code null} — 3-1 참고
     * @param lowFloorRoute 원본의 <i>저상운행</i> 칸. <b>노선 단위다</b> —
     *                    이걸로 '저상버스가 온다'고 말하면 안 된다({@link #lowFloorRoute()})
     */
    public record Route(String routeNo,
                        String originName,
                        String destName,
                        List<Departure> fromOrigin,
                        List<Departure> fromDest,
                        Integer runMin,
                        boolean lowFloorRoute) {

        /**
         * 이 노선에 저상차가 배정돼 있는가.
         *
         * <p><b>★ 이 값은 '지금 오는 버스가 저상'이라는 뜻이 아니다.</b> 한 노선에 저상차와
         * 일반차가 섞여 다니므로, 차량 단위로 판별하는 도착정보({@code vehicletp})만이
         * '탈 수 있다'를 말할 수 있다. 여기서 알 수 있는 것은 <b>'이 노선에 저상차가
         * 다니기는 한다'</b> 까지다.
         *
         * <p>그럼에도 남겨 두는 이유: 보은 저상 노선은 실시간에 아예 안 잡혀서
         * 차량 단위로 확인할 길이 <b>지금은 없다</b>. 아무 말도 안 하는 것보다는
         * '저상 운행 노선인데 차량은 확인할 수 없다'가 사용자에게 쓸모 있다.
         */
        public boolean lowFloorRoute() {
            return lowFloorRoute;
        }
    }

    /** {@code 노선번호 → 시간표}. 같은 번호가 여러 줄로 오면 합친다. */
    private final Map<String, Route> byRouteNo;

    /** 어디서 읽었는지. 로그와 화면 각주에 쓴다 — 시간표는 언제 것인지가 값의 일부다. */
    private final String source;

    private BusTimetable(Map<String, Route> byRouteNo, String source) {
        this.byRouteNo = byRouteNo;
        this.source = source;
    }

    /** 아무것도 못 읽었을 때. {@code null} 대신 이걸 돌려서 부르는 쪽에 널 검사를 퍼뜨리지 않는다. */
    public static BusTimetable empty() {
        return new BusTimetable(Map.of(), "(없음)");
    }

    public boolean isEmpty() {
        return byRouteNo.isEmpty();
    }

    public int routeCount() {
        return byRouteNo.size();
    }

    public String source() {
        return source;
    }

    public Route route(String routeNo) {
        return routeNo == null ? null : byRouteNo.get(routeNo.trim());
    }

    // ------------------------------------------------------------------
    // 읽기
    // ------------------------------------------------------------------

    /**
     * 클래스패스에서 CSV 한 벌을 읽는다.
     *
     * <p>실패해도 예외를 올리지 않는다. 시간표는 곁들이는 정보라, 이것 때문에
     * 버스 탭 전체가 죽으면 실시간이 잡히는 노선까지 같이 못 보게 된다.
     */
    public static BusTimetable load(String classpathResource) {
        if (classpathResource == null || classpathResource.isBlank()) {
            return empty();
        }

        try (InputStream in = BusTimetable.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {

            if (in == null) {
                log.warn("시간표 파일이 클래스패스에 없습니다: {}", classpathResource);
                return empty();
            }
            return parse(in, classpathResource);

        } catch (IOException | RuntimeException e) {
            log.warn("시간표를 읽지 못했습니다 ({}): {}", classpathResource, e.toString());
            return empty();
        }
    }

    private static BusTimetable parse(InputStream in, String source) throws IOException {
        Map<String, Route> out = new LinkedHashMap<>();

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {

            List<String> header = readRecord(r);
            if (header == null) {
                return empty();
            }
            // 엑셀이 UTF-8 로 내보내면서 맨 앞에 BOM 을 붙인다. 그대로 두면 첫 컬럼명이 안 맞는다.
            header.set(0, stripBom(header.get(0)));

            int cNo = indexOf(header, "노선번호");
            int cOrigin = indexOf(header, "기점");
            int cDest = indexOf(header, "종점");
            int cFromOrigin = indexOf(header, "기점출발");
            int cFromDest = indexOf(header, "종점출발");
            int cLow = indexOf(header, "저상운행");

            if (cNo < 0 || cFromOrigin < 0 || cFromDest < 0) {
                log.warn("시간표 컬럼을 알아볼 수 없습니다: {}", header);
                return empty();
            }

            List<String> row;
            while ((row = readRecord(r)) != null) {
                if (row.size() <= cNo) {
                    continue;
                }
                String no = cell(row, cNo);
                if (no.isEmpty()) {
                    continue;
                }

                List<Departure> a = departures(cell(row, cFromOrigin));
                List<Departure> b = departures(cell(row, cFromDest));
                if (a.isEmpty() && b.isEmpty()) {
                    continue;
                }

                Route add = new Route(no,
                        tidy(cell(row, cOrigin)),
                        tidy(cell(row, cDest)),
                        a, b,
                        runMin(a, b),
                        "Y".equalsIgnoreCase(cell(row, cLow)));

                /*
                  같은 번호가 여러 줄로 오는 경우가 있다(방향·편성이 나뉜 노선).
                  줄을 골라 버리면 그 편들이 통째로 사라지므로 시각을 합친다.
                  소요시간은 먼저 알아낸 쪽을 남긴다 — 편성이 달라도 노선 길이는 같다.
                */
                Route old = out.get(no);
                out.put(no, old == null ? add : merge(old, add));
            }
        }

        log.info("시간표 적재 · {} · 노선 {}개", source, out.size());
        return new BusTimetable(out, source);
    }

    private static Route merge(Route x, Route y) {
        return new Route(x.routeNo(),
                x.originName().isEmpty() ? y.originName() : x.originName(),
                x.destName().isEmpty() ? y.destName() : x.destName(),
                mergeTimes(x.fromOrigin(), y.fromOrigin()),
                mergeTimes(x.fromDest(), y.fromDest()),
                x.runMin() != null ? x.runMin() : y.runMin(),
                x.lowFloorRoute() || y.lowFloorRoute());
    }

    private static List<Departure> mergeTimes(List<Departure> x, List<Departure> y) {
        List<Departure> out = new ArrayList<>(x);
        for (Departure d : y) {
            if (out.stream().noneMatch(o -> o.min() == d.min())) {
                out.add(d);
            }
        }
        out.sort(Comparator.comparingInt(Departure::min));
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    // ★ 시각 뽑기 — 여기가 이 파일에서 제일 조심스러운 자리다
    // ------------------------------------------------------------------

    private static final Pattern TIME = Pattern.compile("\\b(\\d{1,2}):(\\d{2})\\b");

    /**
     * 출발시각 칸 하나에서 편들을 뽑는다.
     *
     * <p><b>★ 콤마로 잘라 시각으로 읽으면 안 된다.</b> 원본의 콤마는 두 가지로 쓰인다.
     *
     * <pre>
     *   "07:00,08:00,10:00"        콤마 = 시각 구분
     *   "08:15 주말,휴일 운행"       콤마 = <b>말 안의 쉼표</b>   ← 여기서 갈린다
     * </pre>
     *
     * 그래서 콤마로 조각을 낸 다음, <b>시각이 하나도 없는 조각은 앞 조각의 말이 이어진 것</b>
     * 으로 보고 도로 붙인다. 그렇게 해야 '휴일 운행'이 시각 없는 유령 편이 되지 않고,
     * '주말,휴일 운행'이라는 단서가 08:15 에 제대로 달린다.
     *
     * <p>조각 하나에 시각이 둘 이상 있는 경우도 있다(<i>15:10 17:10</i>). 콤마 대신
     * 공백으로 나열한 것이라 둘 다 담는다. 같은 시각이 두 번 나오면
     * (<i>07:50 동진휴게소 회차(07:50)</i>) 한 번만 담는다 — 뒤엣것은 회차 시각이지 출발이 아니다.
     */
    static List<Departure> departures(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        // 1) 콤마로 나눈 뒤, 시각 없는 조각은 앞에 도로 붙인다
        List<String> parts = new ArrayList<>();
        for (String piece : raw.split(",")) {
            String p = piece.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (!TIME.matcher(p).find() && !parts.isEmpty()) {
                parts.set(parts.size() - 1, parts.get(parts.size() - 1) + "," + p);
            } else {
                parts.add(p);
            }
        }

        // 2) 조각마다 시각을 뽑고, 남은 말을 단서로 붙인다
        List<Departure> out = new ArrayList<>();
        for (String part : parts) {
            Days days = days(part);
            String note = note(part);

            Matcher m = TIME.matcher(part);
            while (m.find()) {
                int h = Integer.parseInt(m.group(1));
                int mi = Integer.parseInt(m.group(2));
                if (h > 23 || mi > 59) {
                    continue;                     // 시각이 아닌 숫자쌍
                }
                int min = h * 60 + mi;
                if (out.stream().noneMatch(d -> d.min() == min)) {
                    out.add(new Departure(min, days, note));
                }
            }
        }

        out.sort(Comparator.comparingInt(Departure::min));
        return List.copyOf(out);
    }

    /**
     * 단서에서 다니는 날을 읽는다.
     *
     * <p>모르는 말은 {@link Days#ALL} 로 둔다 — 없는 편을 있다고 하는 쪽이,
     * 있는 편을 통째로 감춰서 <b>탈 수 있는 버스를 못 보게 하는 것</b>보다 낫다.
     * 단서 원문은 어차피 화면에 같이 나가므로 사람이 마지막으로 걸러낼 수 있다.
     */
    private static Days days(String s) {
        if (s.contains("주말") || s.contains("휴일") || s.contains("토요") || s.contains("일요")) {
            return Days.WEEKEND;
        }
        if (s.contains("평일")) {
            return Days.WEEKDAY;
        }
        return Days.ALL;
    }

    /** 조각에서 시각을 걷어낸 나머지 말. 괄호 안 회차 시각도 같이 걷는다. */
    private static String note(String s) {
        String t = TIME.matcher(s).replaceAll(" ")
                .replaceAll("\\(\\s*\\)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // 조각을 도로 붙일 때 넣은 콤마가 앞뒤에 남는 경우가 있다
        return t.replaceAll("^[,\\s/]+", "").replaceAll("[,\\s/]+$", "");
    }

    // ------------------------------------------------------------------
    // ★ 편도 소요시간 — 복합 경로가 기다리고 있는 값
    // ------------------------------------------------------------------

    /**
     * 종점출발과 기점출발의 차이를 편도 소요시간으로 본다.
     *
     * <pre>
     *   340  기점 09:55  12:55
     *        종점 10:25  13:25
     *        차이   30분    30분   →  보은에서 이식까지 30분
     * </pre>
     *
     * <p><b>왜 이게 소요시간인가</b>: 농어촌버스는 같은 차가 종점에 닿으면 바로 돌아온다.
     * 그래서 <i>기점출발 + 편도 = 종점출발</i> 이 된다. 79개 노선 중 <b>56개에서
     * 이 차이가 편마다 똑같이 나오는데</b>, 우연으로 그렇게 될 값이 아니다.
     *
     * <p><b>추정이라는 것을 잊지 말 것.</b> 회차 대기가 몇 분 끼어 있으면 그만큼 부풀려진다.
     * 정류장 사이 주행시간은 TAGO 4종 어디에도 없어서, 지금 우리가 가진
     * 가장 나은 근사가 이것이다. 화면에는 <i>약 30분</i> 으로 적는다.
     *
     * @return 편마다 차이가 다르면 <b>가운데값</b>. 편 수가 안 맞거나 값이 이상하면 {@code null}
     */
    private static Integer runMin(List<Departure> fromOrigin, List<Departure> fromDest) {
        if (fromOrigin.isEmpty() || fromOrigin.size() != fromDest.size()) {
            return null;
        }

        List<Integer> gaps = new ArrayList<>();
        for (int i = 0; i < fromOrigin.size(); i++) {
            int gap = fromDest.get(i).min() - fromOrigin.get(i).min();

            /*
              0분 이하는 짝이 어긋난 것이고(종점출발이 더 이르다), 3시간을 넘으면
              애초에 왕복 한 쌍이 아니다. 억지로 쓰면 '약 200분' 같은 값이 화면에 나간다.
            */
            if (gap <= 0 || gap > 180) {
                return null;
            }
            gaps.add(gap);
        }

        gaps.sort(Comparator.naturalOrder());
        return gaps.get(gaps.size() / 2);
    }

    /** 노선의 편도 소요시간(분). 모르면 {@code null}. */
    public Integer runMin(String routeNo) {
        Route r = route(routeNo);
        return r == null ? null : r.runMin();
    }

    // ------------------------------------------------------------------
    // 찾기
    // ------------------------------------------------------------------

    /**
     * 오늘 이 시각 뒤에 떠나는 편.
     *
     * <p>오늘 다니지 않는 편(평일 전용인데 일요일)은 아예 빼고 센다. 남는 게 없으면
     * 빈 목록이고, 그것이 <b>'오늘은 끝났다'</b> 는 답이다.
     */
    public static List<Departure> next(List<Departure> all, DayOfWeek day, LocalTime now, int limit) {
        int nowMin = now.getHour() * 60 + now.getMinute();
        return all.stream()
                .filter(d -> d.days().runsOn(day))
                .filter(d -> d.min() >= nowMin)
                .limit(limit)
                .toList();
    }

    /** 오늘 다니는 편이 몇 개인가. 하루 두 편짜리 노선이 흔해서, 이 수 자체가 정보다. */
    public static int todayCount(List<Departure> all, DayOfWeek day) {
        return (int) all.stream().filter(d -> d.days().runsOn(day)).count();
    }

    // ------------------------------------------------------------------
    // 아주 작은 CSV 읽개
    // ------------------------------------------------------------------

    /**
     * 따옴표 안의 콤마와 줄바꿈을 지키며 한 레코드를 읽는다.
     *
     * <p>라이브러리를 들이지 않은 이유: 읽는 파일이 이 표 하나뿐이고 규칙도
     * 표준 CSV 그대로다. 의존성 한 줄보다 이 열몇 줄이 낫다.
     */
    private static List<String> readRecord(BufferedReader r) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;

        int c;
        while ((c = r.read()) != -1) {
            any = true;
            char ch = (char) c;

            if (quoted) {
                if (ch == '"') {
                    r.mark(1);
                    int nxt = r.read();
                    if (nxt == '"') {
                        cur.append('"');          // "" 는 따옴표 한 개
                    } else {
                        quoted = false;
                        if (nxt != -1) {
                            r.reset();
                        }
                    }
                } else {
                    cur.append(ch);
                }
                continue;
            }

            switch (ch) {
                case '"' -> quoted = true;
                case ',' -> {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                case '\r' -> { /* 버린다 */ }
                case '\n' -> {
                    out.add(cur.toString());
                    return out;
                }
                default -> cur.append(ch);
            }
        }

        if (!any) {
            return null;                          // 파일 끝
        }
        out.add(cur.toString());
        return out;
    }

    private static String stripBom(String s) {
        return s.isEmpty() || s.charAt(0) != '﻿' ? s : s.substring(1);
    }

    private static int indexOf(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (name.equals(header.get(i).trim())) {
                return i;
            }
        }
        return -1;
    }

    private static String cell(List<String> row, int i) {
        return i < 0 || i >= row.size() ? "" : row.get(i).trim();
    }

    /**
     * 원본이 자간을 벌려 놓은 이름을 붙인다(<i>성 티 미 원</i> → <i>성티미원</i>).
     *
     * <p>표에서는 칸을 채우려고 벌려 쓴 것인데, 그대로 화면에 내보내면
     * 정류장 이름과 대조가 안 된다.
     */
    private static String tidy(String s) {
        return s == null ? "" : s.replace(" ", "").trim();
    }
}

package kopo.poly.tool;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서울시 도로굴착복구시스템 대민서비스 - 도로점용(굴착)허가공고 크롤러.
 *
 * <p>휠체어 경로탐색 1차 하드필터에 넣을 "공사기간 중인 보도 구간"을 뽑아내는
 * <b>단독 실행 도구</b>다. DB 저장 / 지오코딩 / edge 매칭은 범위 밖.
 *
 * <p>Spring Bean 으로 등록하지 않는다. main 메서드로만 실행한다.
 *
 * <h3>필터링 조건</h3>
 * 서버 검색 폼에 날짜·도로구분 필터가 없으므로 전체를 받아온 뒤 파싱 단계에서 거른다.
 * 적용 순서는 <b>전체 행 파싱 → 조건 A → 조건 B → CSV</b>.
 * <ul>
 *   <li><b>조건 A (기간)</b> - 기준일은 실행 시점의 오늘. 제외 조건은 {@code 공사종료일 < 오늘} 하나뿐이다.
 *       진행 중인 공사와 아직 시작하지 않은 공사는 모두 남긴다.
 *       허가일·허가번호의 연도는 판단에 쓰지 않고 오직 공사기간만 본다.
 *       파싱 실패한 행은 조용히 버리지 않고 경고 로그 + 별도 집계한다.</li>
 *   <li><b>조건 B (도로구분)</b> - '보도'만 수집하고 '차도'는 제외한다.
 *       그 외 값은 CSV 에 넣지 않되 "미분류 도로구분 값"으로 콘솔에 출력한다.</li>
 * </ul>
 * 도로구분·공사기간 모두 <b>상세정보 테이블의 행 단위 값</b>을 쓴다(상단 요약의 공사기간과 다를 수 있음).
 * 공고 1건에 상세행이 여러 개면 조건을 만족하는 행만 각각 별도 row 로 기록한다(공사명 중복 기재).
 *
 * <h3>중복 제거</h3>
 * 조건 A/B 를 통과한 뒤, <b>공사구간 + 도로구분 + 공사기간</b>이 모두 같은 행은 하나만 남긴다.
 * 예정지번호만 다르고 나머지가 같은 행이 흔하고, 하드필터에는 '어디가 언제 막히는가' 만 의미가 있다.
 * 공사명은 키에 넣지 않으므로 이름이 다른 별개 공사도 합쳐질 수 있다.
 * 그 경우는 조용히 넘기지 않고 "중복 제거 중 공사명이 서로 달랐던 건"으로 콘솔에 출력한다.
 * 남기는 쪽은 먼저 만난 행이고, CSV 순서도 먼저 만난 순서다.
 *
 * <h3>실행 방법</h3>
 * <pre>
 *   // 수집 (인자 없이 실행하면 이것. DEFAULT_GU / 올해)
 *   RoaddigCrawler
 *   RoaddigCrawler crawl --gu=중구
 *   RoaddigCrawler crawl --gu=중구 --yearBegin=2025   // 작년 허가건까지 함께 확인
 *   RoaddigCrawler crawl --gu=중구 --noPrefilter      // 사전선별 끄고 전 공고 확인
 *
 *   // 검색 폼 파라미터 조사만 (데이터 수집 안 함)
 *   RoaddigCrawler inspect
 * </pre>
 *
 * <h3>사이트 구조 (2026-08-04 실측)</h3>
 * <ul>
 *   <li>목록은 jqGrid 가 JSON API 를 호출해 그린다. HTML 목록 페이지에는 데이터가 없다.
 *       → {@code POST /intro/prmisn/list.do} 를 직접 호출한다.</li>
 *   <li>이 API 는 {@code loadonce:true} 로 <b>검색조건에 해당하는 전건을 한 번에</b> 돌려준다.
 *       (화면의 페이지 이동은 클라이언트 사이드 페이징) → 목록 단계에 페이지네이션이 없다.
 *       대신 폭주 방지용으로 {@link #MAX_RECORDS_PER_GU} / {@code --limit} 안전장치를 둔다.</li>
 *   <li>상세정보 테이블은 목록에 없고 상세 페이지에만 있다.
 *       → {@code POST /intro/prmisn/infoPage.do} 로 건별 2-hop 요청. 이쪽은 서버 렌더 HTML 이라 Jsoup 으로 파싱된다.</li>
 * </ul>
 */
public final class RoaddigCrawler {

    private static final String BASE = "https://roaddig.seoul.go.kr";
    private static final String LIST_PAGE = BASE + "/intro/prmisn/listPage.do";
    private static final String LIST_API = BASE + "/intro/prmisn/list.do";
    private static final String INFO_PAGE = BASE + "/intro/prmisn/infoPage.do";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";

    /** 공공 시스템 부담 최소화. 이 하한은 절대 낮추지 말 것. */
    private static final long MIN_SLEEP_MS = 1000L;
    private static final long DEFAULT_SLEEP_MS = 1200L;

    /**
     * 자치구 1개당 목록 안전장치. 이보다 많이 오면 뭔가 잘못된 것으로 보고 자른다.
     * 전 연도(20년치) 조회 시 자치구당 1만 건 안팎이므로 그 위로 넉넉히 잡는다.
     */
    private static final int MAX_RECORDS_PER_GU = 50_000;

    private static final int TIMEOUT_MS = 30_000;
    private static final int MAX_RETRY = 3;

    /**
     * {@code --gu} 를 생략했을 때 볼 자치구. IntelliJ 에서 인자 없이 Run 하면 여기가 대상이 된다.
     * 최종 시범 지역은 서울이 아니므로 이 값 자체에 의미는 없다. 작업 중인 구로 바꿔 쓰면 된다.
     */
    private static final String DEFAULT_GU = "중구";

    /** 조건 B 의 판정값. 이 두 가지만 고려하고, 나머지는 미분류로 보고한다. */
    private static final String ROAD_SE_SIDEWALK = "보도";
    private static final String ROAD_SE_ROADWAY = "차도";

    /** 경고 로그가 콘솔을 덮지 않도록 원문 예시는 앞쪽 몇 건만 찍는다. */
    private static final int MAX_WARN_SAMPLES = 10;

    /** 공사기간 원본 형식: {@code 2026.07.23~2026.07.28} */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Spring Boot 4 가 얹어주는 Jackson 3 (tools.jackson) 사용. 목록 API 응답이 JSON 이라 필요. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 세션 쿠키 보관. 목록 페이지 최초 GET 으로 확보한다. */
    private final Map<String, String> cookies = new HashMap<>();
    private final long sleepMs;

    private RoaddigCrawler(long sleepMs) {
        this.sleepMs = Math.max(MIN_SLEEP_MS, sleepMs);
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        // 콘솔 한글 깨짐 방지. IntelliJ 콘솔은 UTF-8 이 기본이라 이걸로 맞는다.
        // (윈도우 cmd 에서 직접 돌릴 때는 chcp 65001 필요)
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        // 인자를 안 주면 crawl. 1단계(inspect)는 파라미터 확정용이라 이미 역할을 다했고,
        // 실제로 쓰는 건 crawl 이므로 IntelliJ 에서 그냥 Run 해도 수집이 돌아가게 한다.
        // 첫 인자가 --옵션 이면 모드 생략으로 보고 crawl 로 취급한다. (예: --gu=강남구)
        String mode = (args.length > 0 && !args[0].startsWith("--")) ? args[0] : "crawl";
        Map<String, String> opt = parseOptions(args);

        long sleep = parseLong(opt.get("sleep"), DEFAULT_SLEEP_MS);
        RoaddigCrawler crawler = new RoaddigCrawler(sleep);

        switch (mode) {
            case "crawl" -> crawler.crawl(opt);
            case "inspect" -> crawler.inspect();
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("사용법: RoaddigCrawler [crawl|inspect] [옵션]");
        System.out.println("  crawl    (기본) 공고 수집 → 조건 A/B 필터 → CSV 저장 + 조건별 제외 집계");
        System.out.println("  inspect        검색 폼의 form/input/select 구조만 출력 (파라미터 조사용, 데이터 수집 안 함)");
        System.out.println();
        System.out.println("  crawl 옵션:");
        System.out.println("    --gu=중구[,강남구...] | --gu=전체    (기본: " + DEFAULT_GU + ")");
        System.out.println("    --yearBegin=2025 --yearEnd=2026      조회년도(서버 검색 파라미터). 기본: 올해만");
        System.out.println("    --year=2026                          시작=종료 를 한 번에 지정");
        System.out.println("    --noPrefilter                        목록 사전선별 끄기(전 공고 상세 조회, 매우 느림)");
        System.out.println("    --limit=100                          자치구당 상세 조회 건수 제한 (기본: 무제한)");
        System.out.println("    --out=crawl-output                   CSV 출력 폴더");
        System.out.println("    --sleep=1200                         요청 간 대기(ms), 최소 " + MIN_SLEEP_MS);
    }

    // --------------------------------------------------------- 1단계: inspect

    /**
     * 목록 페이지의 모든 form / input / select 를 출력한다.
     * 검색 파라미터명을 추측하지 않고 실제 값으로 확정하기 위한 단계.
     */
    private void inspect() throws IOException {
        System.out.println("[inspect] 이 모드는 검색 폼 구조만 출력합니다. 공고/도로구분 데이터는 수집하지 않습니다.");
        System.out.println("[inspect] 데이터 수집은 crawl 모드입니다: RoaddigCrawler crawl --gu=강서구 --year=2026");
        System.out.println();
        System.out.println("[inspect] GET " + LIST_PAGE);
        Document doc = get(LIST_PAGE, LIST_PAGE);
        System.out.println("[inspect] 응답 charset=" + doc.charset() + ", 길이=" + doc.html().length());
        System.out.println();

        Elements forms = doc.select("form");
        System.out.println("=== <form> " + forms.size() + "개 ===");
        for (Element form : forms) {
            System.out.println();
            System.out.printf("[form] id=%s name=%s%n", q(form.id()), q(form.attr("name")));
            System.out.printf("       action=%s%n", q(form.attr("action")));
            System.out.printf("       method=%s%n", q(form.attr("method").isEmpty() ? "get" : form.attr("method")));

            for (Element input : form.select("input")) {
                System.out.printf("  <input>  name=%-16s type=%-8s id=%-18s value=%s%n",
                        q(input.attr("name")), q(input.attr("type")), q(input.id()), q(input.attr("value")));
            }
            for (Element sel : form.select("select")) {
                System.out.printf("  <select> name=%-16s id=%s%n", q(sel.attr("name")), q(sel.id()));
                for (Element o : sel.select("option")) {
                    System.out.printf("      option value=%-14s text=%s%n", q(o.attr("value")), q(o.text()));
                }
            }
            for (Element ta : form.select("textarea")) {
                System.out.printf("  <textarea> name=%s%n", q(ta.attr("name")));
            }
        }

        // 폼 밖에 있는 select 도 있을 수 있으므로 한 번 더 확인
        List<Element> orphanSelects = doc.select("select").stream()
                .filter(sel -> sel.closest("form") == null)
                .toList();
        if (!orphanSelects.isEmpty()) {
            System.out.println();
            System.out.println("=== form 바깥 <select> ===");
            for (Element sel : orphanSelects) {
                System.out.printf("  <select> name=%s id=%s%n", q(sel.attr("name")), q(sel.id()));
            }
        }

        System.out.println();
        System.out.println("=== 확정된 호출 규격 (2026-08-04 실측) ===");
        System.out.println("  목록 : POST " + LIST_API);
        System.out.println("         schAtdrcId, schYearBegin, schYearEnd, schMode, schTotal");
        System.out.println("         → JSON. rows[] 에 검색조건 전건이 한 번에 담겨 온다(페이지네이션 없음).");
        System.out.println("         rows[] 주요 필드: prmisnReqNo, atdrcId, atdrcNm, prmisnNo, cnwNm,");
        System.out.println("                          entNm, cnstrctEntNm, cnwpdDt, prmisnDe");
        System.out.println("  상세 : POST " + INFO_PAGE);
        System.out.println("         prmisnReqNo, schAtdrcId  → 서버 렌더 HTML (도로굴착공사 상세정보 테이블)");

        Map<String, String> guMap = readGuCodes(doc);
        System.out.println();
        System.out.println("=== 자치구 코드 " + guMap.size() + "개 ===");
        guMap.forEach((nm, cd) -> System.out.printf("  %-8s %s%n", nm, cd));
    }

    // ----------------------------------------------------------- 2단계: crawl

    private void crawl(Map<String, String> opt) throws IOException, InterruptedException {
        int thisYear = Year.now().getValue();
        // 조회년도는 조건이 아니라 서버가 요구하는 검색 파라미터다("전체 기간" 옵션이 없어 값을 넣어야만 한다).
        // 게다가 공사기간도 허가일도 아닌 '허가번호에 박힌 연도' 기준이라 판정에 쓸 수 없다.
        //
        // 범위를 넓히면 목록 응답이 그대로 커진다. 실측(강서구, 2026-08-04):
        //   전 연도 8772건 61.5MB / 최근 3년 2317건 16MB / 올해만 408건 2.9MB
        // → 기본값은 올해만. 연도를 박지 않고 현재년도를 쓰므로 해가 바뀌면 자동으로 따라간다.
        //
        // [알려진 한계] 조회년도는 '허가번호에 박힌 연도' 기준이라, 작년 번호를 달고 올해까지
        // 진행되는 공사는 이 범위에 안 잡힌다. 실측상 8월 기준 1건이지만 연초에는 30건 안팎이 된다.
        // 그래서 아래에서 이 한계를 매 실행 로그에 남긴다. 연초에 돌릴 때는 --yearBegin 을 낮출 것.
        String yearBegin = firstNonEmpty(opt.get("yearBegin"), opt.get("year"), String.valueOf(thisYear));
        String yearEnd = firstNonEmpty(opt.get("yearEnd"), opt.get("year"), String.valueOf(thisYear));
        int limit = (int) parseLong(opt.get("limit"), 0);          // 0 = 무제한
        boolean prefilter = !opt.containsKey("noPrefilter");
        Path outDir = Paths.get(firstNonEmpty(opt.get("out"), "crawl-output"));
        Files.createDirectories(outDir);

        // 조건 A 의 기준 날짜 = 실행 시점의 오늘
        LocalDate today = LocalDate.now();

        // 세션 쿠키 확보 + 자치구 코드표 확보
        System.out.println("[crawl] GET " + LIST_PAGE + " (세션 쿠키/자치구 코드 확보)");
        Document listPage = get(LIST_PAGE, LIST_PAGE);
        Map<String, String> guCodes = readGuCodes(listPage);
        if (guCodes.isEmpty()) {
            System.out.println("[crawl] 자치구 select 파싱 실패. inspect 모드로 페이지 구조를 먼저 확인할 것.");
            return;
        }

        List<String> targetGus = resolveTargetGus(opt.get("gu"), guCodes);
        System.out.println("[crawl] 대상 자치구 " + targetGus.size() + "개: " + targetGus);
        System.out.println("[crawl] 조회년도 " + yearBegin + " ~ " + yearEnd
                + " / 요청간격 " + sleepMs + "ms" + (limit > 0 ? " / 자치구당 상세 " + limit + "건 제한" : ""));
        System.out.println("[crawl] 조건A 기준일(오늘) = " + today + " → 공사종료일 < 오늘 이면 제외");
        System.out.println("[crawl] 조건B → 도로구분 '" + ROAD_SE_SIDEWALK + "' 만 수집, '"
                + ROAD_SE_ROADWAY + "' 제외, 그 외는 미분류로 보고");
        System.out.println("[crawl] 목록 사전선별 " + (prefilter ? "ON (--noPrefilter 로 끌 수 있음)" : "OFF (전 공고 상세 조회)"));

        Stats total = new Stats();

        for (String gu : targetGus) {
            String guCode = guCodes.get(gu);
            System.out.println();
            System.out.println("==================== " + gu + " (" + guCode + ") ====================");

            List<Map<String, String>> allNotices = fetchNoticeList(guCode, yearBegin, yearEnd);
            System.out.println("[" + gu + "] 공고 " + allNotices.size() + "건");
            if (allNotices.isEmpty()) {
                continue;
            }

            Stats stats = new Stats();
            List<Map<String, String>> notices = prefilter
                    ? prefilterNotices(allNotices, today, stats)
                    : allNotices;
            if (prefilter) {
                System.out.println("[" + gu + "] 사전선별 → 상세 조회 대상 " + notices.size() + "건 "
                        + "(종료된 공고 " + stats.skippedExpiredNotice + "건 건너뜀, "
                        + "예상 소요 " + estimateMinutes(notices.size()) + ")");
                warnIfAtYearBoundary(gu, notices, yearBegin, String.valueOf(thisYear));
            }
            if (notices.isEmpty()) {
                stats.print("[" + gu + "]");
                total.merge(stats);
                continue;
            }

            int target = (limit > 0) ? Math.min(limit, notices.size()) : notices.size();
            // 중복 제거용. key = 공사구간|도로구분|공사기간, value = 먼저 만난 행.
            // LinkedHashMap 이라 먼저 만난 순서가 CSV 순서로 유지된다.
            Map<String, String[]> csvRows = new LinkedHashMap<>();

            for (int i = 0; i < target; i++) {
                Map<String, String> notice = notices.get(i);
                String reqNo = notice.get("prmisnReqNo");
                String prmisnNo = notice.get("prmisnNo");
                String cnwNm = notice.get("cnwNm");

                sleep();     // 상세 요청 전에 무조건 쉰다
                List<Map<String, String>> details;
                try {
                    details = fetchDetail(reqNo, guCode);
                } catch (IOException e) {
                    stats.detailFailed++;
                    System.out.println("  [" + (i + 1) + "/" + target + "] " + prmisnNo + " 상세 조회 실패: " + e.getMessage());
                    continue;
                }

                if (details.isEmpty()) {
                    stats.noDetailRow++;
                    continue;
                }

                // --- 1) 전체 행 파싱 → 2) 조건 A(기간) → 3) 조건 B(도로구분) → 4) 남은 행만 CSV ---
                for (Map<String, String> d : details) {
                    stats.parsedRows++;

                    String period = pick(d, "공사기간");
                    String roadSe = pick(d, "도로구분").trim();

                    // 조건 A : 공사종료일 < 오늘 이면 제외. 그 외(진행중/미착공)는 모두 수집.
                    LocalDate endDate = parseEndDate(period);
                    if (endDate == null) {
                        stats.dateParseFailed++;
                        // 조용히 버리지 않는다. 앞쪽 몇 건은 원문을 그대로 찍어 표기 변화를 알아챌 수 있게 한다.
                        if (stats.dateParseFailed <= MAX_WARN_SAMPLES) {
                            System.out.println("  [경고] 공사기간 파싱 실패 - " + prmisnNo
                                    + " / 원본='" + period + "' / 공사명=" + cnwNm);
                        }
                        continue;
                    }
                    if (endDate.isBefore(today)) {
                        stats.expired++;
                        continue;
                    }

                    // 조건 B : 보도만 수집. 차도는 제외. 그 외 값은 CSV 에 넣지 않고 미분류로 보고.
                    if (ROAD_SE_ROADWAY.equals(roadSe)) {
                        stats.roadway++;
                        continue;
                    }
                    if (!ROAD_SE_SIDEWALK.equals(roadSe)) {
                        String label = roadSe.isEmpty() ? "(빈값)" : roadSe;
                        count(stats.unclassified, label);
                        if (stats.unclassifiedSamples.size() < MAX_WARN_SAMPLES) {
                            stats.unclassifiedSamples.add("'" + label + "' - " + prmisnNo + " / " + cnwNm);
                        }
                        continue;
                    }

                    // 조건 통과. 여기서 중복 제거.
                    // 같은 공고 안에서 예정지번호만 다르고 나머지가 같은 행, 또는 다른 공고인데
                    // 같은 자리·같은 기간을 파는 행이 있다. 경로탐색 하드필터 입장에서는
                    // '어디가 언제 막히는가' 만 의미가 있으므로 공사명은 키에서 뺀다.
                    String section = pick(d, "공사구간");
                    String key = section + '|' + roadSe + '|' + period;
                    String[] prev = csvRows.get(key);
                    if (prev != null) {
                        stats.duplicate++;
                        // 공사명까지 같으면 그냥 중복. 다르면 서로 다른 공사가 합쳐진 것이므로 남겨서 보여준다.
                        if (!prev[0].equals(cnwNm) && stats.mergedNameSamples.size() < MAX_WARN_SAMPLES) {
                            stats.mergedNameSamples.add(section + " / " + period
                                    + "\n       유지: " + prev[0] + "\n       병합: " + cnwNm);
                        }
                        continue;
                    }
                    csvRows.put(key, new String[]{cnwNm, section, roadSe, period});
                    stats.kept++;
                }

                if ((i + 1) % 10 == 0 || i + 1 == target) {
                    System.out.println("  [" + (i + 1) + "/" + target + "] 상세 수집중... "
                            + "(파싱 " + stats.parsedRows + "행 → 조건 통과 " + csvRows.size() + "행)");
                }
            }

            // 결과는 조회년도가 아니라 '기준일 시점에 유효한 보도 공사'이므로 파일명도 기준일로 단다.
            Path csv = outDir.resolve("roaddig_" + gu + "_보도_" + today.format(FILE_DATE_FMT) + ".csv");
            writeCsv(csv, csvRows.values());
            System.out.println("[" + gu + "] CSV 저장: " + csv.toAbsolutePath() + " (" + csvRows.size() + "행)");

            stats.print("[" + gu + "]");
            total.merge(stats);
        }

        if (targetGus.size() > 1) {
            System.out.println();
            System.out.println("==================== 전체 합계 ====================");
            total.print("[전체]");
        }
    }

    /**
     * 목록 단계 사전선별. <b>조건 A 의 최종 판정이 아니라 상세 요청을 줄이기 위한 사전 컷</b>이다.
     * 통과한 공고는 뒤에서 상세행의 공사기간으로 다시 판정한다.
     *
     * <p>목록 JSON 에 공고 단위 공사기간(cnwpdDt)이 들어 있는데, 실측 표본 12건에서 이 값이
     * 상세행들의 기간 범위와 정확히 일치했다(상세 종료일이 요약을 넘는 경우 0건).
     * 그래서 "요약 종료일 &lt; 오늘" 이면 그 공고의 상세행도 전부 종료된 것으로 보고 건너뛴다.
     * 전 연도를 조회해도 상세 요청이 폭증하지 않게 하는 장치다.
     *
     * <p>다만 표본이 작으므로 안전측으로 설계한다. 기간을 <b>판독할 수 없으면 건너뛰지 않고</b>
     * 상세를 받아 상세행 기준으로 판정한다. 이 컷 자체를 의심할 땐 {@code --noPrefilter} 로 끄고
     * 전 공고를 훑어 결과를 비교하면 된다.
     */
    private static List<Map<String, String>> prefilterNotices(List<Map<String, String>> notices,
                                                              LocalDate today, Stats stats) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> n : notices) {
            LocalDate end = parseEndDate(n.get("cnwpdDt"));
            if (end != null && end.isBefore(today)) {
                stats.skippedExpiredNotice++;
                continue;
            }
            out.add(n);     // 판독 불가(end == null)도 여기로 → 상세에서 판정
        }
        return out;
    }

    /**
     * 조회 범위를 좁혀서 생기는 누락을 드러낸다.
     *
     * <p>범위가 좁아 무언가 빠지면 에러가 나지 않고 <b>결과 숫자만 조용히 줄어든다.</b>
     * 그래서 두 가지를 로그에 남긴다.
     * <ul>
     *   <li>올해만 조회하는 기본 설정이면 - 작년 번호를 단 진행 중인 공사는 구조적으로 못 잡으므로
     *       그 사실을 매번 명시한다(실측: 8월 1건, 연초 30건 안팎).</li>
     *   <li>범위를 넓혀 잡았는데도 시작년도 경계에 미종료 공고가 걸리면 - 그 앞 연도에도
     *       살아있는 공사가 있을 수 있다는 신호이므로 경고한다.</li>
     * </ul>
     */
    private static void warnIfAtYearBoundary(String gu, List<Map<String, String>> aliveNotices,
                                             String yearBegin, String thisYear) {
        if (yearBegin.equals(thisYear)) {
            System.out.println("  [참고] 조회년도를 " + thisYear + " 로 한정했습니다. 이전 연도 허가번호를 달고"
                    + " 아직 진행 중인 공사는 잡히지 않습니다.");
            System.out.println("         연초에 돌릴 때나 누락이 의심되면 --yearBegin="
                    + (Integer.parseInt(thisYear) - 1) + " 로 함께 확인하세요.");
            return;
        }
        long atBoundary = aliveNotices.stream()
                .filter(n -> yearBegin.equals(permitYear(n.get("prmisnNo"))))
                .count();
        if (atBoundary > 0) {
            System.out.println("  [경고] 조회 시작년도(" + yearBegin + ") 허가건 중 아직 안 끝난 공고가 "
                    + atBoundary + "건 있습니다.");
            System.out.println("         그 이전 연도에도 진행 중인 공사가 있을 수 있습니다. "
                    + "--yearBegin=" + (Integer.parseInt(yearBegin) - 1) + " 로 다시 돌려 비교해 보세요.");
        }
    }

    /** 허가번호 {@code 강서구-2026-기타-0024} 에서 연도 부분을 뽑는다. 판정용이 아니라 범위 점검용. */
    private static String permitYear(String prmisnNo) {
        if (prmisnNo == null) {
            return "";
        }
        String[] parts = prmisnNo.split("-");
        return (parts.length > 1) ? parts[1] : "";
    }

    private static String estimateMinutes(int requests) {
        long minutes = Math.round(requests * DEFAULT_SLEEP_MS / 60000.0);
        return (minutes < 1) ? "1분 미만" : (minutes + "분");
    }

    /**
     * 조건 적용 결과 집계. 어떤 조건에서 몇 행이 떨어졌는지 드러나야
     * 결과가 비었을 때 필터 탓인지 수집 탓인지 구분할 수 있다.
     */
    private static final class Stats {
        int parsedRows;         // 파싱한 상세행 총계
        int kept;               // 조건 A/B 통과 + 중복 제거 후 → CSV 기록
        int duplicate;          // 중복 제외 : 공사구간+도로구분+공사기간이 앞선 행과 동일
        int expired;            // 조건 A 제외 : 이미 종료된 공사
        int dateParseFailed;    // 조건 A 판정 불가 : 공사기간 파싱 실패
        int roadway;            // 조건 B 제외 : 차도
        int noDetailRow;        // 상세행이 아예 없는 공고
        int detailFailed;       // 상세 페이지 요청 실패
        int skippedExpiredNotice;   // 사전선별로 상세 요청을 생략한 공고
        final Map<String, Integer> unclassified = new LinkedHashMap<>();   // 조건 B 미분류 값
        final List<String> unclassifiedSamples = new ArrayList<>();
        /** 중복으로 합쳤는데 공사명이 서로 달랐던 건. 같은 자리를 다른 공사가 파는 경우라 눈으로 확인용. */
        final List<String> mergedNameSamples = new ArrayList<>();

        void merge(Stats o) {
            parsedRows += o.parsedRows;
            kept += o.kept;
            duplicate += o.duplicate;
            expired += o.expired;
            dateParseFailed += o.dateParseFailed;
            roadway += o.roadway;
            noDetailRow += o.noDetailRow;
            detailFailed += o.detailFailed;
            skippedExpiredNotice += o.skippedExpiredNotice;
            o.unclassified.forEach((k, v) -> unclassified.merge(k, v, Integer::sum));
            for (String s : o.unclassifiedSamples) {
                if (unclassifiedSamples.size() < MAX_WARN_SAMPLES) {
                    unclassifiedSamples.add(s);
                }
            }
            for (String s : o.mergedNameSamples) {
                if (mergedNameSamples.size() < MAX_WARN_SAMPLES) {
                    mergedNameSamples.add(s);
                }
            }
        }

        void print(String prefix) {
            System.out.println();
            if (skippedExpiredNotice > 0) {
                System.out.println(prefix + " 사전선별로 상세 요청 생략 (공고 단위 종료) : " + skippedExpiredNotice + "건");
            }
            System.out.println(prefix + " 상세행 " + parsedRows + "행 파싱");
            System.out.println("  조건A 제외 (공사종료일 < 오늘) : " + expired + "행");
            System.out.println("  조건A 판정불가 (공사기간 파싱 실패) : " + dateParseFailed + "행");
            System.out.println("  조건B 제외 ('" + ROAD_SE_ROADWAY + "') : " + roadway + "행");
            System.out.println("  조건B 미분류 (CSV 제외) : "
                    + unclassified.values().stream().mapToInt(Integer::intValue).sum() + "행");
            System.out.println("  중복 제외 (공사구간+도로구분+공사기간 동일) : " + duplicate + "행");
            System.out.println("  → CSV 기록 ('" + ROAD_SE_SIDEWALK + "', 중복 제거 후) : " + kept + "행");
            if (noDetailRow > 0) {
                System.out.println("  (참고) 상세행 없는 공고 : " + noDetailRow + "건");
            }
            if (detailFailed > 0) {
                System.out.println("  (참고) 상세 요청 실패 : " + detailFailed + "건");
            }

            if (!mergedNameSamples.isEmpty()) {
                System.out.println();
                System.out.println(prefix + " 중복 제거 중 공사명이 서로 달랐던 건");
                System.out.println("  같은 구간·같은 기간이라 한 행으로 합쳤다. 별개 공사로 봐야 하면 여기서 확인할 것.");
                mergedNameSamples.forEach(s -> System.out.println("     " + s));
            }

            System.out.println();
            System.out.println(prefix + " 미분류 도로구분 값");
            if (unclassified.isEmpty()) {
                System.out.println("  없음 ('" + ROAD_SE_SIDEWALK + "'/'" + ROAD_SE_ROADWAY + "' 두 값만 확인됨)");
            } else {
                unclassified.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .forEach(e -> System.out.printf("  '%s': %d행%n", e.getKey(), e.getValue()));
                System.out.println("  ↑ 예상 못 한 표기다. 조건B 를 손봐야 하는지 확인할 것. 예시:");
                unclassifiedSamples.forEach(s -> System.out.println("     " + s));
            }
        }
    }

    // ------------------------------------------------------------ 목록 / 상세

    /**
     * 목록 JSON API 호출. 검색조건 전건이 한 번에 오므로 페이지 루프가 없다.
     * 응답 구조가 바뀌어 페이지 단위로 오게 되면 records 와 rows 길이가 어긋나므로 경고를 찍는다.
     */
    private List<Map<String, String>> fetchNoticeList(String guCode, String yearBegin, String yearEnd)
            throws IOException, InterruptedException {

        sleep();
        Connection.Response res = request(LIST_API, Connection.Method.POST, LIST_PAGE, Map.of(
                "schAtdrcId", guCode,
                "schYearBegin", yearBegin,
                "schYearEnd", yearEnd,
                "schMode", "선택",
                "schTotal", ""
        ), true);

        JsonNode root = MAPPER.readTree(res.body());
        JsonNode rows = root.path("rows");
        int records = root.path("records").asInt(-1);
        if (!rows.isArray()) {
            System.out.println("  [경고] 목록 응답에 rows 배열이 없음. 응답 앞부분: "
                    + res.body().substring(0, Math.min(300, res.body().length())));
            return List.of();
        }
        if (records >= 0 && records != rows.size()) {
            System.out.println("  [경고] records(" + records + ") != rows(" + rows.size() + "). "
                    + "서버가 페이지 단위 응답으로 바뀌었을 수 있음 - 목록 페이지네이션 재확인 필요.");
        }

        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode row : rows) {
            if (out.size() >= MAX_RECORDS_PER_GU) {
                System.out.println("  [경고] 자치구당 안전장치 " + MAX_RECORDS_PER_GU + "건 도달. 이후는 자른다.");
                break;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("prmisnReqNo", text(row, "prmisnReqNo"));
            m.put("prmisnNo", text(row, "prmisnNo"));
            m.put("cnwNm", text(row, "cnwNm"));
            m.put("entNm", text(row, "entNm"));
            m.put("cnstrctEntNm", text(row, "cnstrctEntNm"));
            m.put("cnwpdDt", text(row, "cnwpdDt"));
            m.put("prmisnDe", text(row, "prmisnDe"));
            if (!m.get("prmisnReqNo").isEmpty()) {
                out.add(m);
            }
        }
        return out;
    }

    /** 상세 페이지 2-hop 요청 → 도로굴착공사 상세정보 테이블 파싱 (1:N). */
    private List<Map<String, String>> fetchDetail(String prmisnReqNo, String guCode) throws IOException {
        Connection.Response res = request(INFO_PAGE, Connection.Method.POST, LIST_PAGE, Map.of(
                "prmisnReqNo", prmisnReqNo,
                "schAtdrcId", guCode
        ), false);
        return parseDetailTable(res.parse());
    }

    /**
     * 상세정보 테이블을 찾아 헤더 기준으로 파싱한다.
     * 컬럼 순서나 부가 테이블이 바뀌어도 견디도록, "도로구분" 헤더를 가진 테이블을 골라 헤더-인덱스로 매핑한다.
     */
    private static List<Map<String, String>> parseDetailTable(Document doc) {
        for (Element table : doc.select("table")) {
            List<String> headers = new ArrayList<>();
            for (Element th : table.select("thead th")) {
                headers.add(th.text().trim());
            }
            if (headers.stream().noneMatch(h -> h.contains("도로구분"))) {
                continue;
            }

            List<Map<String, String>> rows = new ArrayList<>();
            for (Element tr : table.select("tbody tr")) {
                Elements tds = tr.select("td");
                if (tds.isEmpty()) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < tds.size() && i < headers.size(); i++) {
                    row.put(headers.get(i), tds.get(i).text().trim());
                }
                rows.add(row);
            }
            return rows;
        }
        return List.of();
    }

    /**
     * 공사기간 문자열에서 <b>종료일</b>을 뽑는다. 조건 A 는 종료일만 본다.
     *
     * <p>{@code 2026.07.23~2026.07.28} 형식. 파싱 못 하면 null 을 돌려주고,
     * 호출부에서 경고 로그 + 별도 집계로 처리한다. (조용히 버리지 않는다)
     */
    private static LocalDate parseEndDate(String period) {
        if (period == null || period.isBlank()) {
            return null;
        }
        String[] parts = period.trim().split("~");
        // 시작일만 있는 단일 날짜면 그것을 종료일로 본다.
        String end = parts[parts.length - 1].trim();
        try {
            return LocalDate.parse(end, DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 헤더가 "매설관경(mm)" 처럼 단위를 달고 오므로 부분일치로 꺼낸다. */
    private static String pick(Map<String, String> row, String key) {
        for (Map.Entry<String, String> e : row.entrySet()) {
            if (e.getKey().contains(key)) {
                return e.getValue() == null ? "" : e.getValue();
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- HTTP

    private Document get(String url, String referrer) throws IOException {
        return request(url, Connection.Method.GET, referrer, Map.of(), false).parse();
    }

    /** 재시도 + 쿠키 유지 공통 요청. {@code asJson} 이면 content-type 검사를 끄고 본문을 그대로 받는다. */
    private Connection.Response request(String url, Connection.Method method, String referrer,
                                        Map<String, String> data, boolean asJson) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                Connection conn = Jsoup.connect(url)
                        .method(method)
                        .userAgent(USER_AGENT)
                        .referrer(referrer)
                        .header("Accept-Language", "ko-KR,ko;q=0.9")
                        .cookies(cookies)
                        .timeout(TIMEOUT_MS)
                        .maxBodySize(0)          // 목록 JSON 이 수 MB 라 기본 2MB 제한을 풀어야 한다
                        .data(data);
                if (asJson) {
                    conn.ignoreContentType(true).header("X-Requested-With", "XMLHttpRequest");
                }
                Connection.Response res = conn.execute();
                cookies.putAll(res.cookies());
                return res;
            } catch (IOException e) {
                last = e;
                if (attempt < MAX_RETRY) {
                    System.out.println("  [재시도 " + attempt + "/" + (MAX_RETRY - 1) + "] " + url + " - " + e.getMessage());
                    sleepQuietly(sleepMs * attempt);
                }
            }
        }
        throw last;
    }

    private void sleep() throws InterruptedException {
        Thread.sleep(sleepMs);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 출력

    /** 엑셀에서 한글이 깨지지 않도록 UTF-8 with BOM 으로 쓴다. */
    private static void writeCsv(Path path, Collection<String[]> rows) throws IOException {
        // 공사기간은 상단 요약이 아니라 상세정보 테이블(행 단위)의 값. 도로구분과 기준을 행으로 통일한다.
        String[] header = {"공사명", "공사구간", "도로구분", "공사기간"};
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write('﻿');   // BOM
            w.write(csvLine(header));
            for (String[] row : rows) {
                w.write(csvLine(row));
            }
        }
    }

    private static String csvLine(String[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = (cells[i] == null) ? "" : cells[i].replace("\"", "\"\"").replaceAll("[\r\n]+", " ");
            sb.append('"').append(v).append('"');
        }
        return sb.append("\r\n").toString();
    }

    // ---------------------------------------------------------------- 유틸

    /** 목록 페이지의 자치구 select 에서 코드표를 읽는다. 하드코딩 대신 매번 페이지에서 확보. */
    private static Map<String, String> readGuCodes(Document doc) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Element o : doc.select("#schAtdrcId option, select[name=schAtdrcId] option")) {
            String code = o.attr("value").trim();
            String name = o.text().trim();
            if (code.startsWith("CMM_AD_") && !name.isEmpty()) {
                map.putIfAbsent(name, code);
            }
        }
        return map;
    }

    private static List<String> resolveTargetGus(String guOpt, Map<String, String> guCodes) {
        if (guOpt == null || guOpt.isBlank()) {
            return List.of(guCodes.containsKey(DEFAULT_GU) ? DEFAULT_GU : guCodes.keySet().iterator().next());
        }
        if ("전체".equals(guOpt) || "all".equalsIgnoreCase(guOpt)) {
            return new ArrayList<>(guCodes.keySet());
        }
        List<String> out = new ArrayList<>();
        for (String g : guOpt.split(",")) {
            String name = g.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!guCodes.containsKey(name)) {
                System.out.println("[경고] 알 수 없는 자치구: " + name + " (건너뜀). 사용 가능: " + guCodes.keySet());
                continue;
            }
            out.add(name);
        }
        return out;
    }

    /** {@code --key=value} 형태만 옵션으로 인식한다. */
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

    private static void count(Map<String, Integer> dist, String value) {
        dist.merge(value, 1, Integer::sum);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? "" : v.asString();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static long parseLong(String v, long def) {
        try {
            return (v == null || v.isBlank()) ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String q(String v) {
        return (v == null || v.isEmpty()) ? "-" : v;
    }
}

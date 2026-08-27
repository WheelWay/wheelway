package kopo.poly.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import kopo.poly.dto.BusStopDTO;
import kopo.poly.dto.RouteResultDTO;
import kopo.poly.dto.UserPlaceDTO;
import kopo.poly.geocode.GeocodeUnavailableException;
import kopo.poly.geocode.IGeocoder;
import kopo.poly.graph.GraphHolder;
import kopo.poly.graph.RouteGraph;
import kopo.poly.llm.ILlmClient;
import kopo.poly.llm.LlmUnavailableException;
import kopo.poly.service.IBusService;
import kopo.poly.service.IChatService;
import kopo.poly.service.IRouteService;
import kopo.poly.stt.ISttClient;
import kopo.poly.stt.SttUnavailableException;
import kopo.poly.service.IUserPlaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 길 안내 도우미.
 *
 * <p>하는 일은 <b>이어 붙이는 것</b>이다. 목적지 해석(Gemini)·좌표(정류장 캐시·카카오)·
 * 길찾기(IRouteService)·속도(IWalkService)가 전부 이미 있고, 이 클래스는 순서대로 부른다.
 *
 * <h3>★ 숫자는 전부 우리 것이다</h3>
 * 거리·시간·계단 여부는 {@code IRouteService} 가 낸 값만 쓴다. 모델에게는 목적지 이름
 * 하나만 묻고, 답 문장은 템플릿으로 만든다. 문장까지 모델에게 맡기면 호출이 두 배가 되고
 * 숫자를 지어낼 여지가 생긴다.
 *
 * <h3>★ 후보 목록을 우리가 만든다</h3>
 * 전사가 깨진 채로 와도({@code 청주 신의청}) 후보에 정답이 있으면 모델이 복구한다.
 * 카카오는 이걸 못 한다 — {@code 청주 신의청} 은 0건이고 {@code 시청 신청사} 는 평택이 나온다.
 * 그래서 <b>Gemini 를 먼저 거치고 그 다음에 카카오</b>다. 둘을 동시에 던져 시간을 줄이려다
 * 접었다 — 전사문이 {@code 충북대학교까지 어떻게 가요} 같은 문장이라 카카오에 그대로
 * 던져도 안 걸린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService implements IChatService {

    private final ILlmClient llmClient;
    private final IGeocoder geocoder;
    private final IBusService busService;
    private final IRouteService routeService;
    private final IUserPlaceService placeService;
    private final ISttClient sttClient;
    private final GraphHolder graphHolder;

    /** 모델에게 같이 보낼 후보 개수. */
    @Value("${wheelway.chat-candidates}")
    private int candidateLimit;

    /**
     * 도보 기준 속도(m/분). 화면의 {@code baseMinutes} 와 같은 값이어야 한다.
     *
     * <p>그 사람의 실측 속도가 아니다 — {@link #minutesFor} 참고.
     */
    @Value("${wheelway.walk-default-m-per-min}")
    private double walkSpeed;

    /**
     * 이 거리(m)를 넘을 때만 수단을 되묻는다.
     *
     * <p><b>짧으면 묻지 않는다.</b> 300m 앞에 두고 '버스로 갈까요 택시로 갈까요' 를 물으면
     * 그냥 성가시다 — 정류장까지 걸어가 기다리는 시간을 넣으면 그 거리에서는 버스가
     * 반드시 진다({@code TransitResultDTO} 주석의 논지 그대로다).
     *
     * <p>되묻기는 공짜가 아니라 <b>한 번 더 누르게 하는 일</b>이다. 물을 값이 있을 때만 묻는다.
     */
    @Value("${wheelway.chat-ask-mode-over-m}")
    private int askModeOverM;

    /** 지역 전체 정류장을 한 번에 받을 때의 상한. 청주가 2,709곳이라 넉넉히 잡는다. */
    private static final int STOP_POOL = 4000;

    /**
     * 후보로 올릴 최소 겹침(글자 2개짜리 몇 개가 겹치는가).
     *
     * <p><b>★ 이 문턱이 없으면 엉뚱한 곳을 고른다.</b> 처음에는 점수와 무관하게 상위 30개를
     * 그냥 줬는데, {@code 청주시청 가는 길 알려줘} 에 <b>시청 신청사예정지</b> 가 나왔다
     * (2026-08-21). {@code 시청} 하나만 겹치는데도 후보에 올라갔고, 모델은 "후보에 있으면
     * 그것을 고르라" 는 지시를 따랐다.
     *
     * <p>1 은 우연이 너무 많다({@code 시청}·{@code 대학}·{@code 입구} 는 어디에나 있다).
     * 2 부터가 '같은 곳을 가리키는 말' 이다.
     */
    private static final int MIN_OVERLAP = 2;

    @Override
    public Answer askVoice(String username, byte[] wav, Double hereLat, Double hereLng) {
        String heard;
        try {
            long begin = System.currentTimeMillis();
            heard = sttClient.transcribe(wav);
            log.info("전사 {}바이트 → '{}' ({}ms)", wav.length, heard, System.currentTimeMillis() - begin);

        } catch (SttUnavailableException e) {
            /*
              서버가 안 떠 있거나 응답이 없다. '못 알아들었다' 로 답하면 안 된다 —
              그러면 사용자가 자기 발음을 의심하며 계속 다시 말한다. 고칠 사람은 우리다.
            */
            log.warn("음성인식 실패: {}", e.getMessage());
            return fail("지금은 음성으로 들을 수 없습니다.\n아래 입력칸에 쳐 주시면 길을 찾아드립니다.", "");
        }

        if (heard.isBlank()) {
            // 서버는 멀쩡한데 말이 안 잡혔다. 이건 정말로 '못 알아들은' 것이다.
            return fail("잘 못 알아들었습니다. 다시 말씀해 주시거나 아래에 입력해 주세요.", "");
        }

        /*
          ★ 짧게 말하라고 유도하지 않는다.

          한 단어만 오면 오히려 더 틀린다 — whisper 는 음성인식기이면서 언어모델이라
          주변 단어가 힌트가 된다. '청주시청' 한 단어는 '청주 신의청' 이 됐지만
          '청주시청 가는 길 알려줘' 는 정확했다(2026-08-21 실측).
          그래서 여기서 문장을 자르거나 다듬지 않고 그대로 넘긴다.
        */
        return ask(username, heard, hereLat, hereLng);
    }

    @Override
    public Answer ask(String username, String utterance, Double hereLat, Double hereLng) {
        String said = (utterance == null) ? "" : utterance.trim();
        if (said.isEmpty()) {
            return fail("어디로 가시는지 말씀해 주세요.", said);
        }

        // ── ① 출발지. 없으면 여기서 되묻고 끝낸다 — 목적지를 알아내도 쓸 데가 없다.
        double[] start = startPoint(username, hereLat, hereLng);
        if (start == null) {
            return new Answer(
                    "출발지를 먼저 정해야 합니다.\n왼쪽 위 [집] 을 눌러 집 주소를 등록하시거나, "
                            + "현재 위치를 켜고 다시 말씀해 주세요.",
                    said, null, false, null, null, null, 0, 0, true,
                    ILlmClient.Mode.UNKNOWN.name(), false);
        }

        // ── ② 목적지 이름. 모델이 하는 일은 여기까지다.
        ILlmClient.Destination picked;
        try {
            picked = llmClient.extractDestination(said, candidates(said));

        } catch (LlmUnavailableException e) {
            // 한도(429)·타임아웃·키 없음. 챗봇을 멈추지 않고 규칙 기반으로 떨어진다.
            log.warn("목적지 해석 실패, 규칙 기반으로 진행합니다: {}", e.getMessage());
            picked = new ILlmClient.Destination(fallbackDestination(said), true, fallbackMode(said));
        }

        if (!picked.matched() || picked.destination().isBlank()) {
            return fail("'" + said + "' 에서 갈 곳을 찾지 못했습니다.\n"
                    + graphHolder.getRegionId() + " 지역 안의 장소를 말씀해 주세요.", said);
        }

        // ── ③ 좌표. 실제 데이터에서만 가져온다.
        double[] end = locate(picked.destination());
        if (end == null) {
            return new Answer("'" + picked.destination() + "' 의 위치를 찾지 못했습니다.",
                    said, picked.destination(), false, null, null, null, 0, 0, false,
                    picked.mode().name(), false);
        }

        /*
          ── ③.5 어떻게 갈 것인가.

          ★ 버스·택시는 여기서 갈라져 나간다. 도보 경로를 굳이 찾지 않는다 —
            버스 탭은 /api/bus/plan 으로 자기 안을 따로 만들고, 택시 탭은 애초에
            출발·도착과 무관하다(우리가 차를 부르는 것이 아니다). 여기서 Dijkstra 를
            한 번 돌려봐야 아무도 안 보는 선이 된다.
        */
        ILlmClient.Mode mode = picked.mode();

        if (mode == ILlmClient.Mode.TAXI) {
            return new Answer(
                    "장애인콜택시는 저희가 대신 부를 수 없습니다.\n"
                            + "[택시] 에서 어디로 연락하고 어떻게 신청하는지 알려드릴게요.",
                    said, picked.destination(), true, start, end, null, 0, 0, false,
                    mode.name(), false);
        }

        if (mode == ILlmClient.Mode.BUS) {
            return new Answer(
                    "'" + picked.destination() + "' 까지 가는 버스를 찾아볼게요.\n"
                            + "[버스] 에 저상버스로 가는 방법이 뜹니다.",
                    said, picked.destination(), true, start, end, null, 0, 0, false,
                    mode.name(), false);
        }

        // ── ④ 길찾기. 거리·시간은 전부 여기서 나온다.
        RouteResultDTO route;
        try {
            route = routeService.searchRoute(start[0], start[1], end[0], end[1]);
        } catch (RuntimeException e) {
            log.error("챗봇 경로 탐색 실패", e);
            return fail("경로를 계산하지 못했습니다. 잠시 후 다시 말씀해 주세요.", said);
        }

        if (!RouteResultDTO.STATUS_SUCCESS.equals(route.getResultStatus())) {
            /*
              '경로없음' 은 오류가 아니다. 계단·공사로 막혀 정말 못 가는 경우가 있다.

              ★ 그리고 여기가 <b>수단을 물어야 할 가장 중요한 자리</b>다.

              걸어서 못 가는 것과 갈 수 없는 것은 전혀 다른데, 전에는 여기서 대화가 끝나
              사용자가 '이 서비스로는 못 가는 곳' 으로 읽었다. 걸어서 막힌 길도 버스나
              콜택시로는 간다 — 오히려 그 사람에게 가장 필요한 답이 그것이다.

              도보 경로가 없으므로 path 는 null 이고, 화면은 그것을 보고 [도보] 를 빼고
              [버스]·[택시] 만 낸다.
            */
            return new Answer(
                    "'" + picked.destination() + "' 까지 휠체어로 걸어갈 수 있는 길을 찾지 못했습니다.\n"
                            + "계단이나 공사로 막혀 있을 수 있습니다.\n"
                            + "버스나 콜택시로 가는 방법을 볼까요?",
                    said, picked.destination(), true, start, end, null, 0, 0, false,
                    ILlmClient.Mode.UNKNOWN.name(), true);
        }

        int minutes = minutesFor(route.getDistanceM());

        /*
          ★ 수단을 모르면 되묻는다 — 단, 멀 때만.

          '청주시청으로 가주세요' 만으로는 걸어갈지 버스를 탈지 알 수 없다. 그런데도
          전에는 늘 도보로 안내했다. 2km 를 그렇게 안내하면 '약 30분입니다' 가 되는데,
          그 사람이 물어본 것은 '어떻게 가야 하나' 였지 '걸으면 몇 분인가' 가 아니다.

          ★ 도보 경로를 그대로 담아 보낸다. 사용자가 [도보] 를 고르면 화면이 이미 가진
            것을 그리면 되므로 서버를 다시 부르지 않는다 — 되묻기 때문에 왕복이 한 번
            더 늘면 되묻기가 손해가 된다.
        */
        if (mode == ILlmClient.Mode.UNKNOWN && route.getDistanceM() >= askModeOverM) {
            return new Answer(
                    question(picked.destination(), route.getDistanceM(), minutes),
                    said, picked.destination(), true,
                    start, end, route.getPath(), route.getDistanceM(), minutes, false,
                    mode.name(), true);
        }

        return new Answer(
                sentence(picked.destination(), route.getDistanceM(), minutes),
                said, picked.destination(), true,
                start, end, route.getPath(), route.getDistanceM(), minutes, false,
                ILlmClient.Mode.WALK.name(), false);
    }

    // ------------------------------------------------------------------ ① 출발지

    /**
     * 화면이 정한 출발지 → 등록해둔 집 순으로 고른다. 둘 다 없으면 {@code null}.
     *
     * <p><b>화면이 좌표를 줬다는 것은 사용자가 이미 출발지를 정했다는 뜻이다.</b>
     * 검색해서 찍었거나 [현재 위치] 를 눌렀거나 집 버튼을 눌러 넣은 값이다.
     * 그걸 무시하고 집으로 되돌리면, 밖에서 현재위치를 잡아놓고 물었는데
     * 집에서 출발하는 경로가 나온다.
     *
     * <p>화면이 아무것도 안 줬을 때만 집으로 간다. 챗봇에 말을 거는 상황이
     * 대개 집에서 나서기 전이라 그게 맞는 기본값이다.
     */
    private double[] startPoint(String username, Double hereLat, Double hereLng) {
        if (hereLat != null && hereLng != null) {
            return new double[] { hereLat, hereLng };
        }
        if (username != null) {
            UserPlaceDTO home = placeService.get(username, "HOME");
            if (home != null && home.isUsable()) {
                return new double[] { home.getLatitude(), home.getLongitude() };
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ ② 후보

    /**
     * 모델에게 "이 중에서 골라라" 로 줄 이름들.
     *
     * <p><b>정류장 이름으로 만든다.</b> 지역 전체 목록을 이미 캐시하고 있어서 공짜이고,
     * 사람이 말하는 목적지의 상당수가 정류장 이름과 겹친다(청주시청·성안길·충북대학교).
     *
     * <p>고르는 방법은 <b>글자 두 개씩 겹치는 정도</b>다. 전사가 깨져도 소리가 비슷하면
     * 글자도 비슷하게 깨지기 때문에({@code 청주시청} → {@code 청주 신의청}) 걸린다.
     * 정확히 일치하는 것만 찾으면 깨진 전사는 하나도 못 건진다 — 그러면 후보를 주는
     * 의미가 없어진다.
     *
     * <p>이름이 방향별로 중복되므로({@code 보은군청입구} 가 상·하행 두 건) 한 번 접는다.
     */
    private List<String> candidates(String utterance) {
        double[] b = graphHolder.getGraph().bounds();
        if (b == null) {
            return List.of();
        }

        List<BusStopDTO> stops = busService.stopsInBounds(b[0], b[1], b[2], b[3], STOP_POOL);
        if (stops.isEmpty()) {
            return List.of();
        }

        Set<String> said = bigrams(utterance);

        // 이름 → 점수. LinkedHashMap 이라 같은 점수면 먼저 본 순서가 유지된다.
        Map<String, Integer> scored = new LinkedHashMap<>();
        for (BusStopDTO s : stops) {
            String name = s.stopName();
            if (name == null || name.isBlank()) {
                continue;
            }
            int score = overlap(said, bigrams(name));
            scored.merge(name, score, Math::max);
        }

        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : scored.entrySet()) {
            if (e.getValue() >= MIN_OVERLAP) {
                out.add(e.getKey());
            }
        }
        out.sort(Comparator.comparingInt((String n) -> scored.get(n)).reversed());

        // 문턱을 넘는 것이 없으면 빈 목록을 준다. 억지로 채우면 모델이 그 중에서 고른다.
        return out.subList(0, Math.min(candidateLimit, out.size()));
    }

    /** 글자 두 개씩 끊어 담는다. 한 글자만 겹치는 것은 우연이 너무 많다. */
    private static Set<String> bigrams(String s) {
        String t = s.replaceAll("\\s+", "");
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    private static int overlap(Set<String> a, Set<String> b) {
        int n = 0;
        for (String x : b) {
            if (a.contains(x)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 모델을 못 불렀을 때. 조사와 흔한 말꼬리를 떼고 남은 것을 목적지로 본다.
     *
     * <p>정확하지 않다. 정확할 필요도 없다 — <b>챗봇이 멈추지 않게</b> 하는 것이 목적이다.
     * 여기까지 왔다는 것은 이미 한도에 걸렸거나 구글이 죽은 상태다.
     */
    private static String fallbackDestination(String utterance) {
        String t = utterance
                .replaceAll("(가는|가려면|가고|가|갈)?\\s*(길|방법)?\\s*(좀)?\\s*"
                        + "(알려줘|알려주세요|알려|찾아줘|찾아주세요|가르쳐줘)\\s*[.!?]*$", "")
                .replaceAll("(까지|으로|에서|에게|한테|으로는)?\\s*"
                        + "(어떻게|어떡해)\\s*(가요|가나요|가면|가|되나요)?\\s*[.?!]*$", "")
                .replaceAll("(에|로|으로|까지|를|을|이|가)?\\s*"
                        + "(가고\\s*싶어요|가고\\s*싶다|가주세요|갑시다|가자)\\s*[.!?]*$", "")
                .trim();
        return t.isBlank() ? utterance.trim() : t;
    }

    /**
     * 모델을 못 불렀을 때의 수단. <b>여기서만 규칙으로 본다.</b>
     *
     * <p><b>왜 평소에는 규칙을 안 쓰는가</b>: 이 판단에는 함정이 있다.
     * {@code 버스터미널로 가주세요} 는 버스를 타겠다는 말이 아니라 <b>목적지 이름</b>이다.
     * 글자만 보면 그 둘을 못 가른다 — 그건 문장을 읽어야 아는 것이고, 그래서 평소에는
     * 모델이 한다. 여기는 모델이 죽었을 때의 자리라 아래처럼 이름부터 지우고 본다.
     *
     * <p>애매하면 {@link ILlmClient.Mode#UNKNOWN} 이다. 틀리게 정하는 것보다 되묻는 것이 낫다 —
     * 되묻기는 버튼 한 번이고, 틀린 수단은 헛걸음이다.
     */
    private static ILlmClient.Mode fallbackMode(String utterance) {
        String t = utterance.replaceAll("\s+", "");

        // ★ 수단으로 읽으면 안 되는 '이름' 부터 지운다. 지우고도 남아 있어야 진짜 수단이다.
        t = t.replaceAll("(시외|고속|시내)?버스(터미널|정류장|정류소|승강장|차고지)", "")
             .replaceAll("택시(승강장|정류장|타는곳)", "");

        if (t.contains("택시")) {
            return ILlmClient.Mode.TAXI;
        }
        if (t.contains("버스")) {
            return ILlmClient.Mode.BUS;
        }
        if (t.contains("걸어") || t.contains("도보") || t.contains("걸을") || t.contains("걷")) {
            return ILlmClient.Mode.WALK;
        }
        return ILlmClient.Mode.UNKNOWN;
    }

    // ------------------------------------------------------------------ ③ 좌표

    /**
     * 이름을 좌표로. <b>정류장 캐시를 먼저 본다.</b>
     *
     * <p>순서가 중요하다. 카카오 로컬에는 버스정류장 카테고리가 아예 없어서 정류장 이름을
     * 그대로 쳐도 한 건도 안 나온다({@code IBusService.findStops} 주석 참고).
     * 반대로 정류장이 아닌 곳은 캐시에 없다. 둘은 서로를 메우는 관계다.
     *
     * <p>카카오를 부를 때는 <b>반경을 함께 준다.</b> 안 주면 청주 좌표를 줘도 평택 것이 나온다.
     */
    private double[] locate(String name) {
        List<BusStopDTO> hit = busService.findStops(name, null, 1);
        if (!hit.isEmpty()) {
            BusStopDTO s = hit.get(0);
            return new double[] { s.latitude(), s.longitude() };
        }

        double[] b = graphHolder.getGraph().bounds();
        if (b == null) {
            return null;
        }
        double centerLat = (b[0] + b[2]) / 2;
        double centerLng = (b[1] + b[3]) / 2;

        // 안내 구역을 덮을 만큼의 반경. 대각선의 절반이면 구석까지 들어온다.
        int radius = (int) Math.ceil(
                RouteGraph.haversineM(b[0], b[1], b[2], b[3]) / 2);

        try {
            IGeocoder.Point p = geocoder.searchPlace(name, centerLat, centerLng, radius);
            return (p == null) ? null : new double[] { p.latitude(), p.longitude() };

        } catch (GeocodeUnavailableException e) {
            log.warn("장소 검색 실패: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ ④ 답 문장

    /**
     * 걸리는 시간(분). <b>도보 기준으로만 낸다.</b>
     *
     * <h3>★ 개인 실측 배수를 여기에 쓰지 않는다</h3>
     * 처음에는 그 사람의 실측 중앙값을 썼는데 그건 틀렸다.
     * {@code WALK_RECORDS} 는 <b>집에서 타려는 버스가 서는 정류장까지</b> 를 재려고 만든 것이다
     * — 스톱워치가 재는 구간이 그것이고, {@code STOP_NAME} 으로 묶는 이유도 그것이다.
     *
     * <p>그 배수는 '나갈 시각'을 정하는 데 쓴다. 버스를 놓치면 다음 차까지 기다려야 하니
     * 거기서는 느린 쪽으로 잡는 것이 안전하다. 반면 목적지까지 그냥 걸어가는 안내에는
     * 놓칠 차가 없다 — 배수를 곱하면 그저 <b>큰 숫자</b>가 되어 갈 만한 거리를
     * 못 갈 거리처럼 보이게 만든다.
     *
     * <p>그래서 배수가 붙는 자리는 <b>버스가 얽힌 곳뿐</b>이다
     * ({@code minutesToStop} · 복합 경로의 도보 구간). 여기는 아니다.
     *
     * <h3>★ 올림한다</h3>
     * 화면(map.js {@code baseMinutes})과 같은 자를 쓴다. 8.2분을 8분으로 알려주면
     * 매번 조금씩 늦는다. 전에 반올림해서 말풍선은 66분, 경로 패널은 67분이 뜨고 있었다.
     */
    private int minutesFor(double distanceM) {
        if (walkSpeed <= 0) {
            return 0;
        }
        return (int) Math.max(1, Math.ceil(distanceM / walkSpeed));
    }

    /**
     * 답 문장. <b>템플릿이다.</b>
     *
     * <p>모델에게 문장을 맡기지 않는 이유: 호출이 두 배가 되고(RPM 15 에 그만큼 빨리 걸린다),
     * 숫자를 지어낼 여지가 생긴다. 여기 들어가는 값은 전부 탐색이 낸 것이다.
     *
     * <p>'약' 을 붙인다. 휠체어 속도는 개인차가 커서 화면 다른 곳도 전부 그렇게 적는다.
     */
    private static String sentence(String destination, double distanceM, int minutes) {
        return destination + "까지 약 " + minutes + "분입니다. (" + distance(distanceM) + ")\n"
                + "계단과 지하통로를 뺀 길로 안내했습니다.";
    }

    /**
     * 수단을 되물을 때의 문장.
     *
     * <p><b>도보 숫자를 먼저 보여주고 묻는다.</b> 그냥 "어떻게 가시겠어요?" 만 물으면
     * 무엇을 견주어 고르라는 것인지 알 수 없다 — 2.4km 라는 것을 알아야 걸을지 말지
     * 정할 수 있고, 그 값은 이미 우리가 갖고 있다(되물으려고 탐색을 미루지 않는 이유).
     */
    private static String question(String destination, double distanceM, int minutes) {
        return destination + "까지 어떻게 가시겠어요?\n"
                + "걸어가면 약 " + minutes + "분입니다. (" + distance(distanceM) + ")";
    }

    /** 사람이 읽는 거리. 1km 부터는 km 로 접는다. */
    private static String distance(double distanceM) {
        long m = Math.round(distanceM);
        return (m >= 1000) ? String.format("%.1fkm", m / 1000.0) : m + "m";
    }

    private static Answer fail(String message, String said) {
        return new Answer(message, said, null, false, null, null, null, 0, 0, false,
                ILlmClient.Mode.UNKNOWN.name(), false);
    }
}

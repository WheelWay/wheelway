package kopo.poly.bus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 버스 구간을 <b>실제 도로 모양</b>으로 그리기 위한 좌표열을 카카오 길찾기에서 받아온다.
 *
 * <h3>왜 필요한가</h3>
 * TAGO 는 노선 선형을 주지 않는다. 그래서 지금까지 버스 구간은 <b>정류장을 직선으로 이은
 * 꺾은선</b>이었다. 같은 경로의 도보 구간과 나란히 놓고 재보면 이렇다(2026-08-22 실측):
 *
 * <pre>
 *   도보  14.3 m/점   ← 그래프에서 나온 진짜 길      (49점 / 685m)
 *   버스 399.8 m/점   ← 정류장 16개를 이은 직선      (17점 / 6396m)
 * </pre>
 *
 * 28배 차이다. 화면에서 버스 선이 건물을 뚫고 지나가거나 강을 가로지르는 것이 그래서다.
 * 같은 구간을 카카오로 받으면 <b>146점</b>이 되어 도로를 따라간다.
 *
 * <h3>시간은 여기서 안 받는다</h3>
 * 카카오 응답에는 소요시간도 같이 오지만 <b>쓰지 않는다.</b> 승용차 기준이라
 * 버스 시간으로는 지금 쓰는 실측보다 못하다 — 청주 26개 노선에 대고 재보니
 * 실측 대비 오차가 카카오 29.5%, 기존 상수 26.5% 였다(2026-08-22).
 * 여기서 가져오는 것은 <b>모양뿐</b>이다.
 *
 * <h3>경유지로 노선을 강제한다</h3>
 * 그냥 승차→하차만 물으면 카카오는 최적 경로를 주는데, 버스는 마을을 돌아 들어가므로
 * 실제 노선과 다른 길이 나온다. 중간 정류장을 경유지로 넣어 노선 쪽으로 붙인다.
 * 경유지는 최대 5개까지만 받으므로 균등하게 골라 넣는다 — 정류장이 16곳이어도
 * 5곳만 찍히니 완벽하지는 않지만, 직선으로 잇는 것보다는 훨씬 낫다.
 *
 * <h3>안 되면 조용히 물러난다</h3>
 * 키가 없거나, 한도에 걸리거나, 카카오가 느리면 {@code null} 을 준다. 그러면 부르는 쪽이
 * 예전처럼 정류장 직선을 쓴다 — <b>선이 거친 것은 불편이고 경로가 안 나오는 것은 고장이다.</b>
 * 지도 선 하나 때문에 복합 경로가 실패하면 안 된다.
 */
@Slf4j
@Component
public class KakaoRoadPath {

    private static final String DIRECTIONS_API = "https://apis-navi.kakaomobility.com/v1/directions";

    /** 카카오가 받는 경유지 상한. 넘겨 보내면 오류가 난다. */
    private static final int MAX_WAYPOINTS = 5;

    /**
     * 응답을 얼마나 기다릴지.
     *
     * <p>짧게 잡는다. 이 값이 없어도 화면은 그려지므로 여기서 오래 붙들면
     * <b>있으면 좋은 것 때문에 반드시 필요한 것이 늦어진다.</b>
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    /**
     * 받아 둔 선을 몇 개까지 들고 있을지.
     *
     * <p>노선 구간의 모양은 개편이 있을 때나 바뀌므로 오래 들고 있어도 된다.
     * 다만 (노선 × 정류장쌍) 조합은 얼마든지 늘 수 있어서 상한을 둔다.
     * 넘치면 통째로 비운다 — 무엇을 버릴지 고르는 값을 따로 들고 다니느니
     * 다시 받는 편이 싸다(어차피 자주 쓰이는 구간이 금방 다시 찬다).
     */
    private static final int CACHE_MAX = 500;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** {@code credentials/api_keys.properties} 의 {@code kakao.rest-api-key}. 지오코딩과 같은 키다. */
    private final String restKey;

    /** 꺼 두고 싶을 때가 있다. {@code wheelway.bus-road-path} 참고. */
    private final boolean enabled;

    private final HttpClient http;

    /** {@code 노선>승차>하차 → 도로 좌표열}. */
    private final Map<String, List<double[]>> cache = new ConcurrentHashMap<>();

    /** 한 번 크게 실패하면 이번 기동에서는 더 부르지 않는다. 매 요청마다 4초씩 버리지 않으려는 것. */
    private volatile boolean giveUp = false;

    /**
     * 첫 실패를 한 번은 눈에 띄게 남겼는가.
     *
     * <p>이 기능은 실패해도 화면이 멀쩡해서(직선으로 그려진다) <b>고장 난 줄을 모른다.</b>
     * 실제로 경유지 인코딩 버그를 그렇게 놓쳤다. 그래서 첫 실패만 INFO 로 한 줄 남긴다 —
     * 매번 남기면 한도에 걸린 날 로그가 그것으로 도배된다.
     */
    private volatile boolean toldOnce = false;

    public KakaoRoadPath(@Value("${kakao.rest-api-key:}") String restKey,
                         @Value("${wheelway.bus-road-path:true}") boolean enabled) {

        this.restKey = (restKey == null) ? "" : restKey.trim();
        this.enabled = enabled;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        if (enabled && this.restKey.isEmpty()) {
            log.info("kakao.rest-api-key 가 없습니다. 버스 구간은 정류장을 직선으로 이어 그립니다.");
        }
    }

    /**
     * 정류장 좌표열을 도로를 따라가는 좌표열로 바꾼다.
     *
     * @param key   캐시 열쇠. {@code 노선>승차정류장>하차정류장} 처럼 그 구간을 가리키는 값
     * @param stops 승차부터 하차까지의 정류장 좌표 {@code [[위도,경도], ...]}
     * @return 도로 좌표열. <b>못 구하면 {@code null}</b> — 부르는 쪽이 받은 것을 그대로 쓰면 된다
     */
    public List<double[]> alongRoad(String key, List<double[]> stops) {

        if (!enabled || giveUp || restKey.isEmpty() || stops == null || stops.size() < 2) {
            return null;
        }

        List<double[]> hit = cache.get(key);
        if (hit != null) {
            return hit;
        }

        List<double[]> road;
        try {
            road = fetch(stops);
        } catch (RuntimeException e) {
            /*
              한 번 실패했다고 바로 포기하지는 않는다 — 몰아쳤을 때 한 건만 튕기는 일이 흔하다.
              다만 키가 막힌 것이라면 매 요청이 4초씩 늦어지므로, 그건 fetch 안에서 giveUp 을 세운다.
            */
            tellOnce(key, e.toString());
            return null;
        }
        if (road == null) {
            tellOnce(key, "쓸 만한 좌표열이 오지 않았습니다");
            return null;
        }

        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        cache.put(key, road);
        return road;
    }

    /** 첫 실패만 한 줄 남긴다. {@link #toldOnce} 참고. */
    private void tellOnce(String key, String why) {
        if (toldOnce) {
            log.debug("버스 구간 도로 선 실패 ({}): {}", key, why);
            return;
        }
        toldOnce = true;
        log.info("버스 구간을 도로 모양으로 그리지 못했습니다 ({}). 정류장 직선으로 그립니다: {}",
                key, why);
    }

    /** 실제 호출. 실패는 {@code null} 이거나 예외다. */
    private List<double[]> fetch(List<double[]> stops) {

        double[] origin = stops.get(0);
        double[] dest = stops.get(stops.size() - 1);

        // ★ 카카오는 '경도,위도' 순이다. 우리 좌표열은 '위도,경도' 라 뒤집어 넣는다.
        StringBuilder url = new StringBuilder(DIRECTIONS_API)
                .append("?origin=").append(origin[1]).append(',').append(origin[0])
                .append("&destination=").append(dest[1]).append(',').append(dest[0])
                .append("&priority=RECOMMEND");

        List<double[]> mid = stops.subList(1, stops.size() - 1);
        if (!mid.isEmpty()) {
            int n = Math.min(MAX_WAYPOINTS, mid.size());
            double step = (double) mid.size() / n;
            url.append("&waypoints=");
            for (int i = 0; i < n; i++) {
                double[] p = mid.get((int) (i * step));
                if (i > 0) {
                    /*
                      ★ 파이프를 날것으로 넣으면 안 된다. URI 문법에서 허용되지 않는 문자라
                      URI.create 가 IllegalArgumentException 을 던지고, 그것이 아래
                      catch(RuntimeException) 에 걸려 <b>조용히 직선 선으로 돌아간다.</b>
                      실제로 그렇게 됐다(2026-08-22) — 로그도 안 남아서 한참 헤맸다.
                    */
                    url.append("%7C");
                }
                url.append(p[1]).append(',').append(p[0]);
            }
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(TIMEOUT)
                .header("Authorization", "KakaoAK " + restKey)
                .GET()
                .build();

        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(e.getMessage(), e);
        }

        /*
          401 은 키가 틀린 것이고 403 은 이 API 를 못 쓰는 것이다. 둘 다 다시 불러도 그대로라
          이번 기동에서는 접는다 — 안 그러면 모든 요청이 4초씩 늦어진다.
          429(한도)는 접지 않는다. 내일이면 풀리고, 그동안은 직선으로 그리면 된다.
        */
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            giveUp = true;
            log.warn("카카오 길찾기를 쓸 수 없습니다 (HTTP {}). 버스 구간은 직선으로 그립니다.",
                    res.statusCode());
            return null;
        }
        if (res.statusCode() != 200) {
            return null;
        }

        JsonNode root = MAPPER.readTree(res.body());
        JsonNode route = root.path("routes").path(0);
        if (route.path("result_code").asInt(-1) != 0) {
            return null;
        }

        List<double[]> out = new ArrayList<>();
        for (JsonNode section : route.path("sections")) {
            for (JsonNode road : section.path("roads")) {
                JsonNode vx = road.path("vertexes");

                // vertexes 는 [경도, 위도, 경도, 위도, ...] 로 납작하게 온다.
                for (int i = 0; i + 1 < vx.size(); i += 2) {
                    double lng = vx.path(i).asDouble();
                    double lat = vx.path(i + 1).asDouble();

                    // 구간끼리 끝점을 공유해서 같은 점이 잇달아 들어온다. 한 번만 담는다.
                    if (!out.isEmpty()) {
                        double[] last = out.get(out.size() - 1);
                        if (last[0] == lat && last[1] == lng) {
                            continue;
                        }
                    }
                    out.add(new double[] { lat, lng });
                }
            }
        }

        // 점이 정류장 수보다도 적으면 제대로 못 받은 것이다. 그럴 바엔 원래 것을 쓴다.
        return out.size() < stops.size() ? null : out;
    }
}

package kopo.poly.geocode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 카카오 로컬 주소검색으로 주소를 좌표로 바꾼다.
 *
 * <p>{@code ConstructionZoneLoader} 가 공사구간 지오코딩에 쓰던 것과 <b>같은 API</b> 다.
 * 그쪽은 CSV 를 한 번 밀어넣는 CLI 도구라 스프링 밖에 있고, 이 클래스는 화면에서
 * 부를 수 있게 빈으로 둔 것이다.
 *
 * <h3>왜 서버가 부르는가</h3>
 * 브라우저에서 직접 부르면 REST 키가 F12 에 그대로 노출된다. 지도 SDK 에 쓰는 JS 키는
 * 도메인으로 묶여 있어 화면에 내보내도 되지만 REST 키는 그런 보호가 없다.
 *
 * <p>덧: 그래서 이쪽은 <b>도메인 등록과 무관하다.</b> NCP 로 옮길 때 JS 키에 새 도메인을
 * 등록해야 하는 것(20260821 인계서 13-5)은 지도 SDK 얘기지 이 호출과는 상관이 없다.
 *
 * <h3>키가 없으면</h3>
 * 앱은 그대로 뜬다. 주소 등록을 시도할 때만 {@link GeocodeUnavailableException} 이 나고,
 * 화면에 '주소 검색 기능이 설정되지 않았습니다' 가 뜬다. 기동을 막으면 키가 없는 팀원이
 * 아무 화면도 못 본다.
 */
@Slf4j
@Component
public class KakaoGeocoder implements IGeocoder {

    private static final String ADDRESS_API = "https://dapi.kakao.com/v2/local/search/address.json";

    /** 지명·상호 검색. 주소 검색과 엔드포인트가 다르다 — '청주시청' 은 주소가 아니다. */
    private static final String KEYWORD_API = "https://dapi.kakao.com/v2/local/search/keyword.json";

    /** 카카오가 받는 최대 반경. 이보다 크게 보내면 오류가 난다. */
    private static final int MAX_RADIUS_M = 20_000;

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** {@code credentials/api_keys.properties} 의 {@code kakao.rest-api-key}. 없으면 빈 문자열이다. */
    private final String restKey;

    private final HttpClient http;

    public KakaoGeocoder(@Value("${kakao.rest-api-key:}") String restKey) {
        this.restKey = (restKey == null) ? "" : restKey.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();

        if (this.restKey.isEmpty()) {
            log.warn("kakao.rest-api-key 가 없습니다. 주소로 좌표를 찾는 기능이 꺼진 채 뜹니다.");
        }
    }

    @Override
    public Point geocode(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        if (restKey.isEmpty()) {
            throw new GeocodeUnavailableException("주소 검색 기능이 설정되지 않았습니다. (kakao.rest-api-key)");
        }

        // ★ 한글이 들어가므로 반드시 인코딩해서 붙인다. 날것으로 넘기면 깨진 질의가 나가고
        //   0건이 돌아오는데, 증상이 '그런 주소가 없다' 와 똑같아서 원인을 찾기 어렵다.
        //   (20260809 인계서 8-4 · 20260821 인계서 14-2 의 그것이다)
        String url = ADDRESS_API + "?size=1&query=" + URLEncoder.encode(address, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "KakaoAK " + restKey)
                .GET()
                .build();

        String raw;
        try {
            HttpResponse<String> res =
                    http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // 401 은 키가 틀린 것이고 429 는 한도다. 둘 다 '주소가 없음' 이 아니다.
            if (res.statusCode() != 200) {
                throw new GeocodeUnavailableException(
                        "주소 검색 서버가 HTTP " + res.statusCode() + " 를 냈습니다.");
            }
            raw = res.body();

        } catch (GeocodeUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeocodeUnavailableException("주소 검색이 중단되었습니다.", e);
        } catch (Exception e) {
            throw new GeocodeUnavailableException("주소 검색 서버에 연결하지 못했습니다.", e);
        }

        JsonNode docs = MAPPER.readTree(raw).path("documents");
        if (!docs.isArray() || docs.isEmpty()) {
            log.debug("주소를 찾지 못했습니다: {}", address);
            return null;
        }

        JsonNode d = docs.get(0);

        // ★ x 가 경도(longitude), y 가 위도(latitude) 다. 순서를 뒤집으면 좌표가
        //   태평양 어딘가로 가는데, 값이 둘 다 그럴듯한 실수라 눈으로는 안 보인다.
        //   카카오는 이 둘을 문자열로 준다 — 숫자로 넘겨짚지 말고 그대로 받아 파싱한다.
        double lat;
        double lon;
        try {
            lon = Double.parseDouble(d.path("x").asString());
            lat = Double.parseDouble(d.path("y").asString());
        } catch (RuntimeException e) {
            log.warn("주소 검색 결과에 좌표가 없습니다: {}", address);
            return null;
        }

        String matched = d.path("address_name").asString();
        return new Point(lat, lon, (matched == null || matched.isBlank()) ? address : matched);
    }

    @Override
    public Point searchPlace(String keyword, double centerLat, double centerLng, int radiusM) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        if (restKey.isEmpty()) {
            throw new GeocodeUnavailableException("장소 검색 기능이 설정되지 않았습니다. (kakao.rest-api-key)");
        }

        // ★ radius 를 반드시 같이 보낸다. x·y 만으로는 지역이 안 좁혀진다 —
        //   청주 좌표를 주고 '시청 신청사' 를 물었더니 평택 것이 1등으로 나왔다(2026-08-21).
        int radius = Math.min(Math.max(radiusM, 1), MAX_RADIUS_M);

        String url = KEYWORD_API
                + "?size=1"
                + "&query=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&x=" + centerLng          // ★ x 가 경도다
                + "&y=" + centerLat          // ★ y 가 위도다
                + "&radius=" + radius;

        JsonNode d = firstDocument(url, keyword);
        if (d == null) {
            return null;
        }

        double lat;
        double lon;
        try {
            lon = Double.parseDouble(d.path("x").asString());
            lat = Double.parseDouble(d.path("y").asString());
        } catch (RuntimeException e) {
            log.warn("장소 검색 결과에 좌표가 없습니다: {}", keyword);
            return null;
        }

        // place_name 이 사람이 아는 이름이다(주소 검색과 달리 address_name 은 번지다).
        String name = d.path("place_name").asString();
        return new Point(lat, lon, (name == null || name.isBlank()) ? keyword : name);
    }

    /**
     * 부르고 첫 결과를 준다. 없으면 {@code null}.
     *
     * <p>주소 검색과 지명 검색이 응답 모양이 같아서(문서 배열 + meta) 호출부를 나눌 이유가 없다.
     *
     * @param what 로그에 남길 질의어
     */
    private JsonNode firstDocument(String url, String what) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "KakaoAK " + restKey)
                .GET()
                .build();

        String raw;
        try {
            HttpResponse<String> res =
                    http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (res.statusCode() != 200) {
                throw new GeocodeUnavailableException(
                        "장소 검색 서버가 HTTP " + res.statusCode() + " 를 냈습니다.");
            }
            raw = res.body();

        } catch (GeocodeUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeocodeUnavailableException("장소 검색이 중단되었습니다.", e);
        } catch (Exception e) {
            throw new GeocodeUnavailableException("장소 검색 서버에 연결하지 못했습니다.", e);
        }

        JsonNode docs = MAPPER.readTree(raw).path("documents");
        if (!docs.isArray() || docs.isEmpty()) {
            log.debug("장소를 찾지 못했습니다: {}", what);
            return null;
        }
        return docs.get(0);
    }
}

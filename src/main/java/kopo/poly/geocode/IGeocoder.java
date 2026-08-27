package kopo.poly.geocode;

/**
 * 주소 한 줄을 좌표로 바꾼다.
 *
 * <p><b>왜 인터페이스인가</b>: {@code IBusClient} 를 그렇게 만들어 둔 것과 같은 이유다.
 * 지금 구현체는 카카오 하나뿐이지만, 이 자리는 나중에 챗봇의 목적지 해석
 * (Gemini 가 뽑은 지명 → 좌표)이 같이 쓰게 될 곳이다. 그때 카카오가 못 찾는 것을
 * 정류장 캐시로 받는 구현체를 더할 수 있어야 한다.
 */
public interface IGeocoder {

    /**
     * 지오코딩 결과 한 건.
     *
     * @param latitude       위도. 카카오 응답의 {@code y} 다
     * @param longitude      경도. 카카오 응답의 {@code x} 다
     * @param matchedAddress 카카오가 실제로 매칭한 주소. 우리가 보낸 것과 다를 수 있어서 같이 받는다
     */
    record Point(double latitude, double longitude, String matchedAddress) {
    }

    /**
     * 주소를 좌표로. 못 찾으면 {@code null} 이다.
     *
     * <p><b>★ 우편번호와 상세주소를 뗀 원문만 넘긴다.</b> 둘 다 좌표에 아무것도 보태지 않는다.
     *
     * <p>처음에는 "붙이면 0건이 난다"고 적었는데 <b>재보니 그렇지 않았다</b> —
     * 2026-08-21 청주 도로명·지번 8가지로 재보니 {@code "(28644)충북 청주시…"} 도,
     * 뒤에 {@code "101동 1503호"} 를 붙인 것도 전부 같은 좌표를 냈다. 카카오가 견뎌준다.
     * 그래도 원문만 넘기는 이유는 <b>보태는 것이 없고 모든 주소에서 견딘다는 보장이 없어서</b>지,
     * 붙이면 깨지기 때문이 아니다.
     *
     * @throws GeocodeUnavailableException 키가 없거나 카카오가 응답하지 않을 때.
     *         '못 찾았다'({@code null})와 구분해야 한다 — 화면이 할 말이 다르다
     */
    Point geocode(String address);

    /**
     * 지명·상호로 찾는다. 주소가 아니라 {@code 청주시청} · {@code 충북대학교} 같은 말이다.
     *
     * <p>챗봇이 쓴다. 모델이 뽑아준 목적지 이름을 좌표로 바꾸는 자리다.
     *
     * <p><b>★ {@code radiusM} 을 반드시 준다.</b> 중심 좌표(x·y)만 넘기면 카카오는 그것을
     * <b>정렬 힌트로만</b> 쓴다 — 지역을 좁히지 않는다. 2026-08-21 실측에서
     * {@code 시청 신청사} 에 청주 좌표를 줬는데도 <b>평택</b>시청 신청사가 1등으로 나왔다.
     *
     * <p>동명이 전국에 흔하다는 것도 같은 얘기다. 반경을 안 주면 '중앙시장' 이 어디 것인지
     * 아무도 모른다.
     *
     * @param centerLat 안내 중인 지역의 중심
     * @param centerLng 안내 중인 지역의 중심
     * @param radiusM   이 반경 밖은 안 본다. 카카오가 받는 최대는 20,000m 다
     * @return 못 찾으면 {@code null}
     *
     * @throws GeocodeUnavailableException 키가 없거나 카카오가 응답하지 않을 때
     */
    Point searchPlace(String keyword, double centerLat, double centerLng, int radiusM);
}
